// SPDX-License-Identifier: GPL-3.0-or-later
#include <jni.h>
#include <llama.h>
#include <nlohmann/json.hpp>
#include <algorithm>
#include <atomic>
#include <chrono>
#include <cmath>
#include <memory>
#include <mutex>
#include <string>
#include <vector>

namespace {
using Clock = std::chrono::steady_clock;
using Json = nlohmann::ordered_json;
double ms(Clock::time_point start) { return std::chrono::duration<double, std::milli>(Clock::now() - start).count(); }
std::mutex worker;
std::atomic<int64_t> cancelledThrough{0};
struct Abort {
    int64_t request;
    Clock::time_point deadline;
    bool stopped() const { return cancelledThrough.load() >= request || Clock::now() >= deadline; }
};
bool abortDecode(void *data) { return static_cast<Abort *>(data)->stopped(); }
std::unique_ptr<llama_model, decltype(&llama_model_free)> model(nullptr, llama_model_free);
std::unique_ptr<llama_context, decltype(&llama_free)> context(nullptr, llama_free);
std::string loadedPath;
std::vector<llama_token> cachedPrompt;
std::vector<uint8_t> cachedState;
std::vector<float> cachedLogits;
void clear() {
    cachedPrompt.clear(); cachedState.clear(); cachedLogits.clear();
    if (context) llama_memory_clear(llama_get_memory(context.get()), true);
}
void release() { clear(); context.reset(); model.reset(); loadedPath.clear(); }
jbyteArray result(JNIEnv *env, const Json &data) {
    const auto value = data.dump(-1, ' ', false, Json::error_handler_t::replace);
    auto output = env->NewByteArray(static_cast<jsize>(value.size()));
    env->SetByteArrayRegion(output, 0, static_cast<jsize>(value.size()), reinterpret_cast<const jbyte *>(value.data()));
    return output;
}
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_org_fcitx_fcitx5_android_completion_QwenNative_load(JNIEnv *env, jobject, jstring path) {
    std::lock_guard<std::mutex> lock(worker);
    const char *chars = env->GetStringUTFChars(path, nullptr);
    const std::string requested(chars); env->ReleaseStringUTFChars(path, chars);
    const auto start = Clock::now();
    if (model && context && loadedPath == requested) return result(env, {{"state", "ready"}, {"load_ms", 0}, {"reused_model", true}});
    release();
    static std::once_flag backend;
    std::call_once(backend, [] { llama_backend_init(); });
    auto mp = llama_model_default_params(); mp.n_gpu_layers = 0; mp.load_mode = LLAMA_LOAD_MODE_MMAP;
    try {
        model.reset(llama_model_load_from_file(requested.c_str(), mp));
        if (!model) throw std::runtime_error("模型加载失败");
        auto cp = llama_context_default_params();
        cp.n_ctx = 1024; cp.n_batch = 256; cp.n_ubatch = 256; cp.n_seq_max = 1;
        cp.n_threads = 2; cp.n_threads_batch = 2; cp.offload_kqv = false; cp.op_offload = false;
        context.reset(llama_init_from_model(model.get(), cp));
        if (!context) throw std::runtime_error("无法创建推理上下文");
        loadedPath = requested;
        return result(env, {{"state", "ready"}, {"load_ms", ms(start)}, {"reused_model", false}});
    } catch (const std::exception &e) {
        release(); return result(env, {{"state", "error"}, {"load_ms", ms(start)}, {"error", e.what()}});
    }
}

extern "C" JNIEXPORT void JNICALL
Java_org_fcitx_fcitx5_android_completion_QwenNative_cancel(JNIEnv *, jobject, jlong id) {
    auto old = cancelledThrough.load();
    while (old < id && !cancelledThrough.compare_exchange_weak(old, id)) {}
}
extern "C" JNIEXPORT void JNICALL
Java_org_fcitx_fcitx5_android_completion_QwenNative_reset(JNIEnv *, jobject) { std::lock_guard<std::mutex> lock(worker); clear(); }
extern "C" JNIEXPORT void JNICALL
Java_org_fcitx_fcitx5_android_completion_QwenNative_unload(JNIEnv *, jobject) { std::lock_guard<std::mutex> lock(worker); release(); }

extern "C" JNIEXPORT jbyteArray JNICALL
Java_org_fcitx_fcitx5_android_completion_QwenNative_generate(JNIEnv *env, jobject, jbyteArray input, jlong id, jint maxTokens, jint budgetMs) {
    std::lock_guard<std::mutex> lock(worker);
    const auto start = Clock::now();
    Json out = {{"state", "error"}, {"prompt_tokens", 0}, {"generated_tokens", 0}, {"reused_tokens", 0},
                {"prefill_ms", 0}, {"first_token_ms", nullptr}, {"decode_ms", 0}, {"native_ms", 0}, {"text", ""}};
    auto finish = [&]() { out["native_ms"] = ms(start); return result(env, out); };
    if (!context || !model) { out["state"] = "not_ready"; return finish(); }
    Abort abort{id, start + std::chrono::milliseconds(std::max(1, static_cast<int>(budgetMs)))};
    if (abort.stopped()) { out["state"] = "cancelled"; return finish(); }
    llama_set_abort_callback(context.get(), abortDecode, &abort);
    // Remove a pointer to stack memory before returning on any path.
    struct CallbackGuard { ~CallbackGuard() { if (context) llama_set_abort_callback(context.get(), nullptr, nullptr); } } guard;
    const auto length = env->GetArrayLength(input);
    std::string prompt(static_cast<size_t>(length), '\0');
    env->GetByteArrayRegion(input, 0, length, reinterpret_cast<jbyte *>(prompt.data()));
    try {
        const auto *vocab = llama_model_get_vocab(model.get());
        auto count = llama_tokenize(vocab, prompt.data(), length, nullptr, 0, true, false);
        if (count >= 0) { out["state"] = "empty"; return finish(); }
        std::vector<llama_token> tokens(static_cast<size_t>(-count));
        count = llama_tokenize(vocab, prompt.data(), length, tokens.data(), static_cast<int>(tokens.size()), true, false);
        if (count <= 0 || count + maxTokens >= static_cast<int>(llama_n_ctx(context.get()))) { out["state"] = "context_limit"; return finish(); }
        tokens.resize(count); out["prompt_tokens"] = count;
        const auto prefillStart = Clock::now();
        size_t reused = 0;
        // A hybrid/recurrent model cannot roll back arbitrary generated suffixes. Restore the saved
        // prefill state instead. Reuse only an exact token prefix; changed/truncated prefixes reset.
        if (!cachedPrompt.empty() && cachedPrompt.size() <= tokens.size() &&
            std::equal(cachedPrompt.begin(), cachedPrompt.end(), tokens.begin()) && !cachedState.empty()) {
            llama_memory_clear(llama_get_memory(context.get()), true);
            if (llama_state_seq_set_data(context.get(), cachedState.data(), cachedState.size(), 0) > 0) reused = cachedPrompt.size();
        }
        if (!reused) clear();
        out["reused_tokens"] = reused;
        std::vector<float> logits;
        const auto vocabSize = llama_vocab_n_tokens(vocab);
        if (reused == tokens.size()) logits = cachedLogits;
        else {
            for (size_t offset = reused; offset < tokens.size();) {
                const auto size = std::min<size_t>(256, tokens.size() - offset);
                auto batch = llama_batch_get_one(tokens.data() + offset, static_cast<int>(size));
                if (llama_decode(context.get(), batch) != 0) throw std::runtime_error("decode");
                offset += size;
                if (abort.stopped()) throw std::runtime_error("stopped");
            }
            auto *values = llama_get_logits_ith(context.get(), -1);
            if (!values) throw std::runtime_error("logits");
            logits.assign(values, values + vocabSize);
            cachedState.resize(llama_state_seq_get_size(context.get(), 0));
            if (llama_state_seq_get_data(context.get(), cachedState.data(), cachedState.size(), 0) == 0) cachedState.clear();
            cachedPrompt = tokens; cachedLogits = logits;
        }
        out["prefill_ms"] = ms(prefillStart);
        const auto decodeStart = Clock::now();
        std::string text;
        int produced = 0;
        for (int step = 0; step < std::clamp(static_cast<int>(maxTokens), 1, 32); ++step) {
            if (abort.stopped()) throw std::runtime_error("stopped");
            auto token = static_cast<llama_token>(std::max_element(logits.begin(), logits.end()) - logits.begin());
            if (llama_vocab_is_eog(vocab, token) || (llama_vocab_get_attr(vocab, token) & LLAMA_TOKEN_ATTR_CONTROL)) break;
            std::vector<char> piece(256);
            auto size = llama_token_to_piece(vocab, token, piece.data(), static_cast<int>(piece.size()), 0, false);
            if (size < 0) { piece.resize(-size); size = llama_token_to_piece(vocab, token, piece.data(), static_cast<int>(piece.size()), 0, false); }
            if (size <= 0) break;
            text.append(piece.data(), size); ++produced;
            if (produced == 1) out["first_token_ms"] = ms(start);
            // Stop at a phrase boundary. Do not turn a small completion into an essay.
            if (text.find_first_of("\r\n") != std::string::npos || text.find("。") != std::string::npos ||
                text.find("！") != std::string::npos || text.find("？") != std::string::npos ||
                text.find("，") != std::string::npos || text.find("；") != std::string::npos) break;
            const auto codepoints = std::count_if(text.begin(), text.end(), [](unsigned char c) { return (c & 0xc0) != 0x80; });
            if (codepoints >= 12) break;
            auto batch = llama_batch_get_one(&token, 1);
            if (llama_decode(context.get(), batch) != 0) throw std::runtime_error("decode");
            auto *values = llama_get_logits_ith(context.get(), -1);
            if (!values) throw std::runtime_error("logits");
            logits.assign(values, values + vocabSize);
        }
        out["decode_ms"] = ms(decodeStart); out["generated_tokens"] = produced;
        if (abort.stopped()) throw std::runtime_error("stopped");
        out["state"] = text.empty() ? "empty" : "generated";
        out["text"] = text;
    } catch (const std::exception &) {
        out["state"] = cancelledThrough.load() >= id ? "cancelled" : Clock::now() >= abort.deadline ? "timeout" : "inference_error";
        out["text"] = ""; clear();
    }
    return finish();
}
