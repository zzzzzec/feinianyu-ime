// SPDX-License-Identifier: GPL-3.0-or-later
#include "suzu-reranker.h"
#include "model/suzu_model.h"
#include <android/log.h>
#include <algorithm>
#include <chrono>
#include <cmath>
#include <sstream>

SuzuReranker::SuzuReranker() : worker_(&SuzuReranker::run, this) {}
SuzuReranker::~SuzuReranker() {
    { std::lock_guard<std::mutex> lock(mutex_); stopping_ = true; }
    wake_.notify_all();
    worker_.join();
}
void SuzuReranker::configure(const std::string &model, const std::string &vocab) {
    std::lock_guard<std::mutex> lock(mutex_);
    if (modelPath_ == model && vocabPath_ == vocab && state_ != "error") return;
    modelPath_ = model; vocabPath_ = vocab;
    state_ = "loading"; configure_ = true;
    wake_.notify_one();
}
void SuzuReranker::clearContext() {
    std::lock_guard<std::mutex> lock(mutex_);
    ++epoch_;
    context_.clear(); candidates_.clear(); result_.clear(); queued_ = false; clear_ = true;
    wake_.notify_one();
}
bool SuzuReranker::score(const std::string &context, const std::vector<std::string> &candidates,
                         std::vector<double> &scores, SuzuScoreInfo *info) {
    const auto start = std::chrono::steady_clock::now();
    auto describe = [&](const std::string &reason) {
        if (info) {
            info->reason = reason;
            info->elapsedMs = std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - start).count();
        }
    };
    std::unique_lock<std::mutex> lock(mutex_);
    if (state_ != "ready") { describe(state_); return false; }
    if (busy_ || queued_ || clear_) { describe("busy"); return false; }
    const auto ticket = ++request_;
    context_ = context; candidates_ = candidates; queued_ = true;
    wake_.notify_one();
    if (!finished_.wait_for(lock, std::chrono::milliseconds(40), [&] { return completed_ == ticket || stopping_; })) {
        ++timeouts_; describe("timeout"); return false;
    }
    describe(stopping_ ? "stopping" : resultReason_);
    if (info) { info->modelMs = lastMs_; info->unknownCandidates = resultUnknown_; }
    if (stopping_ || result_.size() != candidates.size()) return false;
    scores = result_;
    return true;
}
std::string SuzuReranker::status() {
    std::lock_guard<std::mutex> lock(mutex_);
    std::ostringstream out;
    out << "{\"model\":\"Suzu-2.4\",\"state\":\"" << state_
        << "\",\"scored\":" << scored_ << ",\"timeouts\":" << timeouts_
        << ",\"last_ms\":" << lastMs_ << "}";
    return out.str();
}
void SuzuReranker::run() {
    std::unique_ptr<suzu::SuzuModel> model;
    while (true) {
        std::unique_lock<std::mutex> lock(mutex_);
        wake_.wait(lock, [&] { return stopping_ || configure_ || queued_ || clear_; });
        if (stopping_) return;
        if (clear_) {
            clear_ = false; busy_ = true;
            lock.unlock(); if (model) model->reset(); lock.lock();
            busy_ = false;
            continue;
        }
        if (configure_) {
            suzu::ModelOptions options;
            options.model_path = modelPath_; options.vocab_path = vocabPath_;
            options.n_threads = 2; options.max_candidates = 10;
            options.context_slots = 1; options.gen_beam = 0; options.repack = false;
            configure_ = false; busy_ = true;
            lock.unlock();
            std::string error;
            try { model = suzu::SuzuModel::load(options, &error); }
            catch (...) { model.reset(); }
            lock.lock();
            state_ = model ? "ready" : "error"; busy_ = false;
            __android_log_print(ANDROID_LOG_INFO, "Suzu", "model %s", state_.c_str());
            continue;
        }
        auto context = std::move(context_);
        auto candidates = std::move(candidates_);
        const auto ticket = request_, epoch = epoch_;
        queued_ = false; busy_ = true;
        lock.unlock();
        const auto start = std::chrono::steady_clock::now();
        std::vector<double> scores;
        std::vector<int> unknown;
        std::string reason = "inference_error";
        try {
            if (model) {
                const auto &vocab = model->vocab();
                const auto prefix = vocab.encode_context(context, 30);
                std::vector<suzu::TokenSeq> encoded;
                bool known = !prefix.empty();
                for (size_t i = 0; i < candidates.size(); ++i) {
                    const auto &word = candidates[i];
                    auto tokens = vocab.encode_candidate(word);
                    if (std::find(tokens.begin(), tokens.end(), suzu::kUnkToken) != tokens.end()) unknown.push_back(static_cast<int>(i));
                    encoded.push_back(std::move(tokens));
                }
                known = known && unknown.empty();
                suzu::DecodeStats stats;
                if (known && !model->score(prefix, encoded, &scores, &stats)) scores.clear();
                reason = prefix.empty() ? "short_context" : !unknown.empty() ? "out_of_vocab" : scores.empty() ? "inference_error" : "scored";
            }
        } catch (...) { if (model) model->reset(); scores.clear(); }
        // Text and encoded token buffers are local to this request. KV is cleared
        // at editor boundaries, including a boundary that happened during decode.
        context.clear(); candidates.clear();
        lock.lock();
        if (epoch != epoch_) { lock.unlock(); if (model) model->reset(); lock.lock(); scores.clear(); reason = "context_cleared"; }
        result_ = std::move(scores); completed_ = ticket; busy_ = false;
        resultReason_ = std::move(reason); resultUnknown_ = std::move(unknown);
        lastMs_ = std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - start).count();
        if (!result_.empty()) ++scored_;
        finished_.notify_all();
    }
}
