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
#include <stdexcept>
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
// Normalize over the entire vocabulary, before any top-k/diversity filtering.
double logNormalizer(const std::vector<float> &values) {
    const double highest = *std::max_element(values.begin(), values.end());
    if (!std::isfinite(highest)) throw std::runtime_error("invalid_logits");
    double total = 0;
    for (const auto value : values) total += std::exp(static_cast<double>(value) - highest);
    if (!std::isfinite(total) || total <= 0) throw std::runtime_error("invalid_logits");
    return highest + std::log(total);
}
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
size_t charCount(const std::string &text) {
    return std::count_if(text.begin(), text.end(), [](unsigned char c) { return (c & 0xc0) != 0x80; });
}
std::string limitChars(const std::string &text, size_t limit) {
    const auto valid = completeUtf8(text);
    size_t count = 0;
    for (size_t i = 0; i < valid.size(); ++i) {
        if ((static_cast<unsigned char>(valid[i]) & 0xc0) != 0x80 && count++ == limit) return valid.substr(0, i);
    }
    return valid;
}
bool lineBreak(const std::string &text) {
    return text.find_first_of("\r\n") != std::string::npos || text.find("\\n") != std::string::npos || text.find("\\r") != std::string::npos;
}
bool bridgePiece(const std::string &text) {
    if (text.empty()) return false;
    const auto begin = text.find_first_not_of(" ");
    if (begin == std::string::npos) return true;
    const auto end = text.find_last_not_of(" ");
    const auto middle = text.substr(begin, end - begin + 1);
    return middle == "," || middle == "，" || middle == ":" || middle == "：";
}
const char *tokenKind(const llama_vocab *vocab, llama_token token) {
    if (llama_vocab_is_eog(vocab, token) || (llama_vocab_get_attr(vocab, token) & LLAMA_TOKEN_ATTR_CONTROL)) return "end";
    const auto piece = tokenPiece(vocab, token);
    if (lineBreak(piece)) return "line_break";
    bool word = false; completeUtf8(piece, &word);
    return word ? "word" : "separator";
}
bool phraseDone(const std::string &text, int maxChars) {
    if (text.find_first_of("\r\n") != std::string::npos) return true;
    for (const std::string mark : {"。", "！", "？", "，", "；", "：", ".", "!", "?", ",", ";", ":"}) {
        if (text.size() >= mark.size() && text.compare(text.size() - mark.size(), mark.size(), mark) == 0) return true;
    }
    return charCount(text) >= static_cast<size_t>(maxChars);
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
Java_org_fcitx_fcitx5_android_completion_QwenNative_generate(JNIEnv *env, jobject, jbyteArray input, jlong id, jint maxTokens, jint budgetMs,
    jdouble minRoot, jdouble minToken, jdouble relativeRoot, jint maxChars, jint minChars, jdouble minSingle) {
    std::lock_guard<std::mutex> lock(worker);
    const auto start = Clock::now();
    Json out = {{"state", "error"}, {"prompt_tokens", 0}, {"generated_tokens", 0}, {"reused_tokens", 0},
                {"prefill_ms", 0}, {"first_token_ms", nullptr}, {"decode_ms", 0}, {"native_ms", 0},
                {"candidate_count", 0}, {"requested_candidates", candidateLimit}, {"candidates", Json::array()},
                {"candidate_scores", Json::array()}, {"text", ""}};
    minRoot = std::clamp(minRoot, 0.0, 1.0); minToken = std::clamp(minToken, 0.0, 1.0);
    relativeRoot = std::clamp(relativeRoot, 0.0, 1.0); minSingle = std::clamp(minSingle, 0.0, 1.0);
    maxChars = std::clamp(static_cast<int>(maxChars), 2, 12);
    minChars = std::clamp(static_cast<int>(minChars), 1, static_cast<int>(maxChars));
    out["thresholds"] = {{"min_root_p", minRoot}, {"min_next_token_p", minToken},
        {"relative_root", relativeRoot}, {"max_chars", maxChars}, {"min_chars", minChars}, {"min_single_char_p", minSingle}};
    auto decodeStart = start;
    bool decoding = false;
    auto finish = [&]() { if (decoding) out["decode_ms"] = ms(decodeStart); out["native_ms"] = ms(start); return result(env, out); };
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
        if (count <= 0 || count + maxTokens + 2 >= static_cast<int>(llama_n_ctx_seq(context.get()))) { out["state"] = "context_limit"; return finish(); }
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
        decodeStart = Clock::now(); decoding = true;

        const auto initialToken = static_cast<llama_token>(std::max_element(logits.begin(), logits.end()) - logits.begin());
        out["top_token_p"] = std::exp(logits[initialToken] - logNormalizer(logits));
        out["top_token_kind"] = tokenKind(vocab, initialToken);
        // A high-probability comma/space belongs to the continuation. Never discard it and then
        // force low-probability words (e.g. the line following an unpunctuated poem prefix).
        std::string bridge;
        std::vector<double> bridgeProbabilities;
        for (int i = 0; i < 2; ++i) {
            auto token = static_cast<llama_token>(std::max_element(logits.begin(), logits.end()) - logits.begin());
            const auto piece = tokenPiece(vocab, token);
            if (!bridgePiece(piece)) break;
            const auto p = std::exp(logits[token] - logNormalizer(logits));
            if (p < minToken || charCount(bridge + piece) + minChars > static_cast<size_t>(maxChars)) {
                out["state"] = "abstained"; out["abstain_reason"] = "uncertain_separator"; return finish();
            }
            bridge += piece; bridgeProbabilities.push_back(p);
            out["bridge_tokens"] = bridgeProbabilities.size(); out["bridge_probabilities"] = bridgeProbabilities;
            out["generated_tokens"] = bridgeProbabilities.size();
            if (bridgeProbabilities.size() == 1) out["first_token_ms"] = ms(start);
            auto single = llama_batch_get_one(&token, 1);
            if (llama_decode(context.get(), single) != 0) throw std::runtime_error("decode");
            if (abort.stopped()) throw std::runtime_error("stopped");
            auto *values = llama_get_logits_ith(context.get(), -1);
            if (!values) throw std::runtime_error("logits");
            logits.assign(values, values + vocabSize);
        }
        out["bridge_tokens"] = bridgeProbabilities.size();
        out["bridge_probabilities"] = bridgeProbabilities;

        // Deterministic diverse roots, in model-logit order. Excluding nested token pieces avoids
        // branches such as "休" and "休息" producing the same phrase. Every option is model-generated.
        std::vector<llama_token> ranked(vocabSize);
        std::iota(ranked.begin(), ranked.end(), 0);
        const auto search = std::min<size_t>(128, ranked.size());
        std::partial_sort(ranked.begin(), ranked.begin() + search, ranked.end(), [&](auto a, auto b) {
            return logits[a] == logits[b] ? a < b : logits[a] > logits[b];
        });
        const auto rootNormalizer = logNormalizer(logits);
        const auto rootTopP = std::exp(logits[ranked[0]] - rootNormalizer);
        out["root_top_p"] = rootTopP;
        out["root_top_kind"] = tokenKind(vocab, ranked[0]);
        if (out["root_top_kind"] != "word") {
            out["state"] = "abstained"; out["abstain_reason"] = "stop_or_separator_preferred"; return finish();
        }
        struct Branch { llama_seq_id seq; llama_token token; std::string text; std::vector<float> logits;
            std::vector<double> probabilities; std::string stop = "max_tokens"; bool done = false; double stoppedP = -1; };
        std::vector<Branch> branches;
        std::vector<std::string> roots;
        int lowRoots = 0;
        for (size_t i = 0; i < search && branches.size() < candidateLimit; ++i) {
            const auto token = ranked[i];
            if (!std::isfinite(logits[token]) || llama_vocab_is_eog(vocab, token) ||
                (llama_vocab_get_attr(vocab, token) & LLAMA_TOKEN_ATTR_CONTROL)) continue;
            const auto piece = tokenPiece(vocab, token);
            bool word = false;
            if (completeUtf8(piece, &word) != piece || !word || lineBreak(piece) ||
                std::any_of(piece.begin(), piece.end(), [](unsigned char c) { return c < 0x20; })) continue;
            const auto p = std::exp(logits[token] - rootNormalizer);
            if (p < minRoot || p < rootTopP * relativeRoot) { ++lowRoots; continue; }
            const auto first = piece.find_first_not_of(" \t");
            const auto key = piece.substr(first == std::string::npos ? 0 : first);
            if (std::any_of(roots.begin(), roots.end(), [&](const auto &other) {
                return key.compare(0, other.size(), other) == 0 || other.compare(0, key.size(), key) == 0;
            })) continue;
            roots.push_back(key);
            branches.push_back({static_cast<llama_seq_id>(branches.size()), token, bridge, {}, {}});
        }
        out["branch_count"] = branches.size();
        out["low_confidence_roots"] = lowRoots;
        if (branches.empty()) { out["state"] = "abstained"; out["abstain_reason"] = "low_root_confidence"; return finish(); }
        for (size_t i = 1; i < branches.size(); ++i) {
            llama_memory_seq_cp(llama_get_memory(context.get()), 0, static_cast<llama_seq_id>(i), -1, -1);
        }
        auto batch = llama_batch_init(candidateLimit, 0, 1);
        struct BatchGuard { llama_batch &value; ~BatchGuard() { llama_batch_free(value); } } batchGuard{batch};
        int produced = static_cast<int>(bridgeProbabilities.size());
        for (int step = 0; step < std::clamp(static_cast<int>(maxTokens), 1, 32); ++step) {
            if (abort.stopped()) throw std::runtime_error("stopped");
            batch.n_tokens = 0;
            std::vector<size_t> slots;
            for (size_t i = 0; i < branches.size(); ++i) {
                auto &branch = branches[i];
                if (branch.done) continue;
                if (step > 0) branch.token = static_cast<llama_token>(std::max_element(branch.logits.begin(), branch.logits.end()) - branch.logits.begin());
                const auto token = branch.token;
                if (llama_vocab_is_eog(vocab, token) || (llama_vocab_get_attr(vocab, token) & LLAMA_TOKEN_ATTR_CONTROL)) {
                    branch.done = true; branch.stop = "end"; continue;
                }
                const auto piece = tokenPiece(vocab, token);
                if (piece.empty() || lineBreak(piece)) { branch.done = true; branch.stop = "line_break"; continue; }
                if (std::any_of(piece.begin(), piece.end(), [](unsigned char c) { return c < 0x20; })) { branch.done = true; branch.stop = "control"; continue; }
                const auto lp = step == 0 ? logits[token] - rootNormalizer : branch.logits[token] - logNormalizer(branch.logits);
                const auto p = std::exp(lp);
                if (step > 0 && p < minToken) { branch.done = true; branch.stop = "low_token_confidence"; branch.stoppedP = p; continue; }
                branch.probabilities.push_back(p);
                branch.text += piece; ++produced;
                if (produced == 1) out["first_token_ms"] = ms(start);
                if (phraseDone(branch.text, maxChars) || step + 1 >= maxTokens) {
                    branch.done = true; branch.stop = phraseDone(branch.text, maxChars) ? "boundary_or_length" : "max_tokens"; continue;
                }
                const auto slot = batch.n_tokens++;
                batch.token[slot] = token; batch.pos[slot] = count + static_cast<int>(bridgeProbabilities.size()) + step;
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
        Json stops = Json::object();
        int tooShort = 0;
        for (const auto &branch : branches) {
            stops[branch.stop] = stops.value(branch.stop, 0) + 1;
            const auto text = limitChars(branch.text, maxChars);
            const auto body = text.size() >= bridge.size() ? text.substr(bridge.size()) : std::string();
            const auto first = body.find_first_not_of(" \t");
            const auto last = body.find_last_not_of(" \t");
            if (first == std::string::npos || branch.probabilities.empty()) { ++tooShort; continue; }
            const auto usefulChars = charCount(body.substr(first, last - first + 1));
            if (usefulChars < static_cast<size_t>(minChars) && (usefulChars == 0 || branch.probabilities.front() < minSingle)) { ++tooShort; continue; }
            if (!text.empty() && std::find(out["candidates"].begin(), out["candidates"].end(), text) == out["candidates"].end()) {
                out["candidates"].push_back(text);
                double jointLog = 0;
                for (const auto p : bridgeProbabilities) jointLog += std::log(p);
                for (const auto p : branch.probabilities) jointLog += std::log(p);
                out["candidate_scores"].push_back({{"token_probabilities", branch.probabilities},
                    {"root_p", branch.probabilities.front()}, {"min_p", *std::min_element(branch.probabilities.begin(), branch.probabilities.end())},
                    {"joint_p", std::exp(jointLog)}, {"mean_p", std::exp(jointLog / (bridgeProbabilities.size() + branch.probabilities.size()))},
                    {"stop_reason", branch.stop}, {"stop_token_p", branch.stoppedP >= 0 ? Json(branch.stoppedP) : Json(nullptr)},
                    {"chars", charCount(text)}, {"body_chars", usefulChars}});
            }
        }
        out["stop_counts"] = stops; out["too_short_candidates"] = tooShort;
        out["candidate_count"] = out["candidates"].size();
        out["state"] = out["candidates"].empty() ? "abstained" : "generated";
        if (out["candidates"].empty()) out["abstain_reason"] = "no_usable_candidate";
        if (!out["candidates"].empty()) out["text"] = out["candidates"][0];
    } catch (const std::exception &) {
        out["state"] = cancelledThrough.load() >= id ? "cancelled" : Clock::now() >= abort.deadline ? "timeout" : "inference_error";
        out["text"] = ""; out["candidates"] = Json::array(); out["candidate_scores"] = Json::array(); out["candidate_count"] = 0; clear();
    }
    return finish();
}
