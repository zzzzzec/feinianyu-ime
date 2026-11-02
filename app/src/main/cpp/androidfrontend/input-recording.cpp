// SPDX-License-Identifier: GPL-3.0-or-later
#include "androidfrontend.h"
#include "pinyin.h"
#include <fcitx/candidatelist.h>
#include <fcitx/inputpanel.h>
#include <fcitx/inputmethodmanager.h>
#include <sstream>
#include <cmath>
#include <locale>

namespace fcitx {
// Shares the pinned engine's PinyinState declaration. No guessed/reconstructed pinyin.
bool AndroidFrontend::recordingAllowed() const {
    if (!recordingEnabled_ || recordingSession_.empty() || !activeIC_ || !allowDiagnosticText_) return false;
    auto flags = activeInputContext()->capabilityFlags();
    return !flags.test(CapabilityFlag::Password) && !flags.test(CapabilityFlag::Sensitive);
}
void AndroidFrontend::setInputRecording(const std::string &session, bool enabled, const std::string &context) {
    if (session != recordingSession_ || enabled != recordingEnabled_) {
        recordingList_.reset(); recordingOriginal_.clear(); recordingDecision_ = 0;
        recordingComposing_ = false; recordingAction_ = "null";
    }
    recordingSession_ = session; recordingEnabled_ = enabled; recordingContext_ = enabled ? context : "";
}
void AndroidFrontend::emitRecording(const std::string &type, const std::string &fields) {
    if (!recordingAllowed()) return;
    recordingCallback_("{\"session_id\":" + recordingQuote(recordingSession_) + ",\"type\":" + recordingQuote(type) +
        ",\"decision_id\":" + std::to_string(recordingDecision_) + ",\"composition_id\":" + std::to_string(recordingComposition_) + fields + "}");
}
std::string AndroidFrontend::recordingInput() {
    auto *entry = instance_->inputMethodEntry(activeInputContext());
    const auto mode = entry ? entry->uniqueName() : "";
    std::ostringstream out; out.imbue(std::locale::classic());
    out << "{\"mode\":" << recordingQuote(mode) << ",\"preedit\":" << recordingQuote(activeInputContext()->inputPanel().preedit().toString());
    if (mode == "pinyin" || mode == "shuangpin") {
        auto *state = static_cast<PinyinState *>(activeInputContext()->property("pinyinState"));
        if (state) {
            auto &ctx = state->context_;
            const auto &raw = ctx.userInput();
            if (!raw.empty() && !recordingComposing_) { ++recordingComposition_; recordingComposing_ = true; }
            out << ",\"raw_input\":" << recordingQuote(raw) << ",\"raw_cursor\":" << ctx.cursor()
                << ",\"selected_input_length\":" << ctx.selectedLength()
                << ",\"selected_text\":" << recordingQuote(ctx.selectedSentence())
                << ",\"pinyin_mode\":" << static_cast<int>(state->mode_);
            if (raw.empty()) recordingComposing_ = false;
        } else out << ",\"raw_input\":null";
    } else out << ",\"raw_input\":null";
    out << '}'; return out.str();
}
bool AndroidFrontend::beginRecordingCandidates(const std::shared_ptr<CandidateList> &list) {
    if (!recordingAllowed() || recordingList_.lock() == list) return false;
    recordingList_ = list; recordingOriginal_.clear(); recordingDecision_ = ++recordingSequence_;
    recordingOriginalKnown_ = rankedList_.lock() != list;
    recordingDecisionInput_ = recordingInput();
    auto *bulk = list->toBulk();
    int total = bulk ? bulk->totalSize() : list->size();
    for (int i = 0; i < (total < 0 ? 16 : std::min(total, 16)); ++i) {
        try { recordingOriginal_[&(bulk ? bulk->candidateFromAll(i) : list->candidate(i))] = i + 1; }
        catch (const std::invalid_argument &) { break; }
    }
    return true;
}
void AndroidFrontend::recordCandidates(const std::string &reason, const RankingTrace &trace) {
    if (!recordingAllowed()) return;
    auto list = activeInputContext()->inputPanel().candidateList(); if (!list) return;
    auto *bulk = list->toBulk(); int total = bulk ? bulk->totalSize() : list->size();
    std::ostringstream out; out.imbue(std::locale::classic());
    out << ",\"input\":" << recordingDecisionInput_ << ",\"context\":" << recordingQuote(recordingContext_)
        << ",\"scoring_context\":" << recordingQuote(trace.context)
        << ",\"original_order_known\":" << (recordingOriginalKnown_ ? "true" : "false")
        << ",\"total_candidates\":" << total << ",\"pool_limit\":16,\"ranking_reason\":" << recordingQuote(reason)
        << ",\"ranking_ms\":" << trace.elapsedMs << ",\"suzu_enabled\":" << (suzuEnabled_ ? "true" : "false") << ",\"candidates\":[";
    for (int i = 0; i < (total < 0 ? 16 : std::min(total, 16)); ++i) {
        try {
            const auto &word = bulk ? bulk->candidateFromAll(i) : list->candidate(i);
            int original = recordingOriginal_.count(&word) ? recordingOriginal_.at(&word) : i + 1;
            if (i) out << ',';
            out << "{\"id\":" << recordingQuote(std::to_string(recordingDecision_) + ":" + std::to_string(original))
                << ",\"text\":" << recordingQuote(instance_->outputFilter(activeInputContext(), word.text()).toString())
                << ",\"commit_text\":" << recordingQuote(word.text().toStringForCommit())
                << ",\"original_rank\":" << original << ",\"final_rank\":" << i + 1 << ",\"consumed_range\":null";
            if (original <= static_cast<int>(trace.candidates.size())) {
                auto &score = trace.candidates[original - 1];
                if (score.rawScore && std::isfinite(*score.rawScore)) out << ",\"model_score\":" << *score.rawScore;
                if (score.finalScore && std::isfinite(*score.finalScore)) out << ",\"final_score\":" << *score.finalScore;
            }
            out << '}';
        } catch (const std::invalid_argument &) { break; }
    }
    out << ']'; emitRecording("candidates", out.str());
}
void AndroidFrontend::recordAction(const std::string &kind, int sym, bool up, int index, uint32_t states) {
    if (!recordingAllowed()) { recordingAction_ = "null"; return; }
    auto input = recordingInput();
    std::ostringstream out; out.imbue(std::locale::classic());
    out << "{\"id\":" << ++recordingSequence_ << ",\"kind\":" << recordingQuote(kind)
        << ",\"sym\":" << sym << ",\"states\":" << states << ",\"up\":" << (up ? "true" : "false") << ",\"index\":" << index
        << ",\"index_scope\":" << recordingQuote(pagingMode_ ? "page" : "all") << ",\"input\":" << input;
    auto list = activeInputContext()->inputPanel().candidateList();
    out << ",\"decision_id\":" << (list && recordingList_.lock() == list ? recordingDecision_ : 0);
    if (kind == "candidate_click" && list && index >= 0) {
        try {
            auto *bulk = list->toBulk();
            const auto &word = !pagingMode_ && bulk ? bulk->candidateFromAll(index) : list->candidate(index);
            out << ",\"selected_text\":" << recordingQuote(instance_->outputFilter(activeInputContext(), word.text()).toString());
        } catch (const std::invalid_argument &) {}
    }
    out << '}'; recordingAction_ = out.str();
    emitRecording("action", ",\"action\":" + recordingAction_);
}
void AndroidFrontend::recordActionResult(bool handled) {
    if (recordingAction_ != "null") {
        auto after = recordingInput();
        emitRecording("action_result", ",\"action\":" + recordingAction_ + ",\"handled\":" + (handled ? "true" : "false") + ",\"input\":" + after);
    }
    recordingAction_ = "null";
}
void AndroidFrontend::recordPage(const std::vector<CandidateEntity> &words, int offset, int total, const std::string &kind) {
    if (!recordingAllowed()) return;
    std::ostringstream out; out.imbue(std::locale::classic());
    out << ",\"delivery\":" << recordingQuote(kind) << ",\"offset\":" << offset << ",\"total_candidates\":" << total << ",\"candidates\":[";
    for (size_t i = 0; i < words.size(); ++i) {
        if (i) out << ',';
        out << "{\"text\":" << recordingQuote(words[i].text) << ",\"comment\":" << recordingQuote(words[i].comment) << ",\"label\":" << recordingQuote(words[i].label) << ",\"display_rank\":";
        if (offset >= 0) out << offset + i + 1; else out << "null";
        out << '}';
    }
    out << ']'; emitRecording("candidate_page", out.str());
}
} // namespace fcitx
