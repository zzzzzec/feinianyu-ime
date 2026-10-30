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
#include <numeric>
#include <string>
#include <vector>

namespace {
using Clock = std::chrono::steady_clock;
using Json = nlohmann::ordered_json;
constexpr int candidateLimit = 3;
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
// Token pieces can split UTF-8. Never return a dangling byte sequence at a length limit.
std::string completeUtf8(const std::string &value, bool *hasWord = nullptr) {
    size_t valid = 0;
    bool word = false;
    for (size_t i = 0; i < value.size();) {
        const auto first = static_cast<unsigned char>(value[i]);
        const size_t width = first < 0x80 ? 1 : first >= 0xc2 && first <= 0xdf ? 2 :
            first >= 0xe0 && first <= 0xef ? 3 : first >= 0xf0 && first <= 0xf4 ? 4 : 0;
        if (!width || i + width > value.size()) break;
        uint32_t cp = first & (width == 1 ? 0x7f : (1u << (7 - width)) - 1);
        bool correct = true;
        for (size_t j = 1; j < width; ++j) {
            const auto c = static_cast<unsigned char>(value[i + j]);
            if ((c & 0xc0) != 0x80) { correct = false; break; }
            cp = (cp << 6) | (c & 0x3f);
        }
        if (!correct || (width == 2 && cp < 0x80) || (width == 3 && cp < 0x800) ||
            (width == 4 && cp < 0x10000) || cp > 0x10ffff || (cp >= 0xd800 && cp <= 0xdfff)) break;
        word = word || (cp >= 'a' && cp <= 'z') || (cp >= 'A' && cp <= 'Z') ||
            (cp >= '0' && cp <= '9') || (cp >= 0x3400 && cp <= 0x9fff) || (cp >= 0x20000 && cp <= 0x3134f);
        i += width; valid = i;
    }
    if (hasWord) *hasWord = word;
    return value.substr(0, valid);
}
std::string tokenPiece(const llama_vocab *vocab, llama_token token) {
    std::vector<char> piece(256);
    auto size = llama_token_to_piece(vocab, token, piece.data(), static_cast<int>(piece.size()), 0, false);
    if (size < 0) { piece.resize(-size); size = llama_token_to_piece(vocab, token, piece.data(), static_cast<int>(piece.size()), 0, false); }
    return size > 0 ? std::string(piece.data(), size) : std::string();
}
bool phraseDone(const std::string &text) {
    if (text.find_first_of("\r\n") != std::string::npos) return true;
    for (const std::string mark : {"。", "！", "？", "，", "；"}) {
        if (text.size() >= mark.size() && text.compare(text.size() - mark.size(), mark.size(), mark) == 0) return true;
    }
    return std::count_if(text.begin(), text.end(), [](unsigned char c) { return (c & 0xc0) != 0x80; }) >= 12;
}
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
        cp.n_ctx = 1024 * candidateLimit; cp.n_batch = 256; cp.n_ubatch = 256; cp.n_seq_max = candidateLimit;
        cp.kv_unified = true; // Share the prompt KV pages across the three continuations.
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
                {"prefill_ms", 0}, {"first_token_ms", nullptr}, {"decode_ms", 0}, {"native_ms", 0},
                {"candidate_count", 0}, {"requested_candidates", candidateLimit}, {"candidates", Json::array()}, {"text", ""}};
    auto finish = [&]() { out["native_ms"] = ms(start); return result(env, out); };
    if (!context || !model) { out["state"] = "not_ready"; return finish(); }
    Abort abort{id, start + std::chrono::milliseconds(std::max(1, static_cast<int>(budgetMs)))};
    if (abort.stopped()) { out["state"] = cancelledThrough.load() >= id ? "cancelled" : "timeout"; return finish(); }
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
        if (count <= 0 || count + maxTokens >= static_cast<int>(llama_n_ctx_seq(context.get()))) { out["state"] = "context_limit"; return finish(); }
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

        // Deterministic diverse roots, in model-logit order. Excluding nested token pieces avoids
        // branches such as "休" and "休息" producing the same phrase. Every option is model-generated.
        std::vector<llama_token> ranked(vocabSize);
        std::iota(ranked.begin(), ranked.end(), 0);
        const auto search = std::min<size_t>(128, ranked.size());
        std::partial_sort(ranked.begin(), ranked.begin() + search, ranked.end(), [&](auto a, auto b) {
            return logits[a] == logits[b] ? a < b : logits[a] > logits[b];
        });
        struct Branch { llama_seq_id seq; llama_token token; std::string text; std::vector<float> logits; bool done = false; };
        std::vector<Branch> branches;
        std::vector<std::string> roots;
        for (size_t i = 0; i < search && branches.size() < candidateLimit; ++i) {
            const auto token = ranked[i];
            if (!std::isfinite(logits[token]) || llama_vocab_is_eog(vocab, token) ||
                (llama_vocab_get_attr(vocab, token) & LLAMA_TOKEN_ATTR_CONTROL)) continue;
            const auto piece = tokenPiece(vocab, token);
            bool word = false;
            if (completeUtf8(piece, &word) != piece || !word || piece.find_first_of("\r\n") != std::string::npos) continue;
            const auto first = piece.find_first_not_of(" \t");
            const auto key = piece.substr(first == std::string::npos ? 0 : first);
            if (std::any_of(roots.begin(), roots.end(), [&](const auto &other) {
                return key.compare(0, other.size(), other) == 0 || other.compare(0, key.size(), key) == 0;
            })) continue;
            roots.push_back(key);
            branches.push_back({static_cast<llama_seq_id>(branches.size()), token, {}, {}});
        }
        out["branch_count"] = branches.size();
        if (branches.size() < 2) { out["state"] = "empty"; return finish(); }
        for (size_t i = 1; i < branches.size(); ++i) {
            llama_memory_seq_cp(llama_get_memory(context.get()), 0, static_cast<llama_seq_id>(i), -1, -1);
        }
        auto batch = llama_batch_init(candidateLimit, 0, 1);
        struct BatchGuard { llama_batch &value; ~BatchGuard() { llama_batch_free(value); } } batchGuard{batch};
        int produced = 0;
        for (int step = 0; step < std::clamp(static_cast<int>(maxTokens), 1, 32); ++step) {
            if (abort.stopped()) throw std::runtime_error("stopped");
            batch.n_tokens = 0;
            std::vector<size_t> slots;
            for (size_t i = 0; i < branches.size(); ++i) {
                auto &branch = branches[i];
                if (branch.done) continue;
                if (step > 0) branch.token = static_cast<llama_token>(std::max_element(branch.logits.begin(), branch.logits.end()) - branch.logits.begin());
                const auto token = branch.token;
                if (llama_vocab_is_eog(vocab, token) || (llama_vocab_get_attr(vocab, token) & LLAMA_TOKEN_ATTR_CONTROL)) { branch.done = true; continue; }
                const auto piece = tokenPiece(vocab, token);
                if (piece.empty()) { branch.done = true; continue; }
                branch.text += piece; ++produced;
                if (produced == 1) out["first_token_ms"] = ms(start);
                if (phraseDone(branch.text) || step + 1 >= maxTokens) { branch.done = true; continue; }
                const auto slot = batch.n_tokens++;
                batch.token[slot] = token; batch.pos[slot] = count + step;
                batch.n_seq_id[slot] = 1; batch.seq_id[slot][0] = branch.seq; batch.logits[slot] = 1;
                slots.push_back(i);
            }
            if (!batch.n_tokens) break;
            // All active branches decode together: one prompt prefill and shared weight reads.
            if (llama_decode(context.get(), batch) != 0) throw std::runtime_error("decode");
            for (size_t slot = 0; slot < slots.size(); ++slot) {
                auto *values = llama_get_logits_ith(context.get(), static_cast<int>(slot));
                if (!values) throw std::runtime_error("logits");
                branches[slots[slot]].logits.assign(values, values + vocabSize);
            }
        }
        out["decode_ms"] = ms(decodeStart); out["generated_tokens"] = produced;
        if (abort.stopped()) throw std::runtime_error("stopped");
        for (const auto &branch : branches) {
            const auto text = completeUtf8(branch.text);
            if (!text.empty() && std::find(out["candidates"].begin(), out["candidates"].end(), text) == out["candidates"].end()) out["candidates"].push_back(text);
        }
        out["candidate_count"] = out["candidates"].size();
        out["state"] = out["candidates"].size() >= 2 ? "generated" : "empty";
        if (!out["candidates"].empty()) out["text"] = out["candidates"][0];
    } catch (const std::exception &) {
        out["state"] = cancelledThrough.load() >= id ? "cancelled" : Clock::now() >= abort.deadline ? "timeout" : "inference_error";
        out["text"] = ""; out["candidates"] = Json::array(); out["candidate_count"] = 0; clear();
    }
    return finish();
}
