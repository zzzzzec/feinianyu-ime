/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2023 Fcitx5 for Android Contributors
 */
#include <fcitx/addonfactory.h>
#include <fcitx/addonmanager.h>
#include <fcitx/candidatelist.h>
#include <fcitx/focusgroup.h>
#include <fcitx/inputcontextmanager.h>
#include <fcitx/inputmethodengine.h>
#include <fcitx/inputmethodmanager.h>
#include <fcitx/inputpanel.h>
#include <fcitx/statusarea.h>
#include <fcitx-utils/event.h>
#include <fcitx-utils/utf8.h>
#include <cmath>
#include <numeric>

#include "androidfrontend.h"

namespace fcitx {

class AndroidInputContext : public InputContextV2 {
public:
    AndroidInputContext(AndroidFrontend *frontend,
                        InputContextManager &inputContextManager,
                        int uid,
                        const std::string &pkgName)
            : InputContextV2(inputContextManager, pkgName),
              frontend_(frontend),
              uid_(uid) {
        created();
    }

    ~AndroidInputContext() override {
        frontend_->releaseInputContext(uid_);
        destroy();
    }

    [[nodiscard]] const char *frontend() const override { return "androidfrontend"; }

    void commitStringImpl(const std::string &text) override {
        frontend_->commitString(text, -1);
    }

    void commitStringWithCursorImpl(const std::string &text, size_t cursor) override {
        frontend_->commitString(text, static_cast<int>(cursor));
    }

    void forwardKeyImpl(const ForwardKeyEvent &key) override {
        frontend_->forwardKey(key.rawKey(), key.isRelease());
    }

    void deleteSurroundingTextImpl(int offset, unsigned int size) override {
        const int before = -offset;
        const int after = offset + static_cast<int>(size);
        if (before < 0 || after < 0) {
            FCITX_WARN() << "Invalid deleteSurrounding request: offset=" << offset << ", size="
                         << size;
            return;
        }
        frontend_->deleteSurrounding(before, after);
    }

    void updatePreeditImpl() override {
        frontend_->updateClientPreedit(filterText(inputPanel().clientPreedit()));
    }

    void updateInputPanel() {
        const InputPanel &ip = inputPanel();
        if (ip.empty()) {
          frontend_->updateInputPanel(/*preedit=*/{},
                                      /*auxUp=*/ip.overlayMessage(),
                                      /*auxDown=*/{},
                                      /*tabs=*/{});
          return;
        }
        std::vector<CandidateActionEntity> tabs;
        auto list = ip.candidateList();
        if (list) {
            auto tabbed = ip.candidateList()->toTabbed();
            if (tabbed) {
                auto tabActions = tabbed->tabActions();
                for (const auto &a: tabActions) {
                    tabs.emplace_back(a);
                }
            }
        }
        frontend_->updateInputPanel(
                filterText(ip.preedit()),
                filterText(ip.auxUp()),
                filterText(ip.auxDown()),
                tabs
        );
    }

    void updateCandidatesBulk() {
        std::vector<CandidateEntity> candidates;
        int total = 0;
        const auto &list = inputPanel().candidateList();
        if (list) {
            const auto &bulk = list->toBulk();
            if (bulk) {
                total = bulk->totalSize();
                // limit candidate count to 16 (for paging)
                const int limit = total < 0 ? 16 : std::min(total, 16);
                for (int i = 0; i < limit; i++) {
                    try {
                        // maybe unnecessary; I don't see anywhere using `CandidateWord::setPlaceHolder`
                        // if (candidate.isPlaceHolder()) continue;
                        candidates.emplace_back(candidateEntity(bulk->candidateFromAll(i)));
                    } catch (const std::invalid_argument &e) {
                        FCITX_WARN() << "updateCandidatesBulk(): " << e.what();
                        total = static_cast<int>(candidates.size());
                        break;
                    }
                }
            } else {
                total = list->size();
                for (int i = 0; i < total; i++) {
                    candidates.emplace_back(candidateEntity(list->candidate(i)));
                }
            }
        }
        frontend_->updateCandidateList(candidates, total);
    }

    void updateCandidatesPaged() {
        const auto &list = inputPanel().candidateList();
        if (!list) {
            frontend_->updatePagedCandidate(PagedCandidateEntity::Empty);
            return;
        }
        int cursorIndex = list->cursorIndex();
        CandidateLayoutHint layoutHint = list->layoutHint();
        bool hasPrev = false;
        bool hasNext = false;
        const auto &pageable = list->toPageable();
        if (pageable) {
            hasPrev = pageable->hasPrev();
            hasNext = pageable->hasNext();
        }
        int size = list->size();
        std::vector<CandidateEntity> candidates;
        candidates.reserve(size);
        for (int i = 0; i < size; i++) {
            candidates.emplace_back(candidateEntityWithLabel(list->label(i), list->candidate(i)));
        }
        PagedCandidateEntity paged(candidates, cursorIndex, layoutHint, hasPrev, hasNext);
        frontend_->updatePagedCandidate(paged);
    }

    bool selectCandidateBulk(int idx) {
        const auto &list = inputPanel().candidateList();
        if (!list) {
            return false;
        }
        const auto &bulk = list->toBulk();
        try {
            if (bulk) {
                bulk->candidateFromAll(idx).select(this);
            } else {
                list->candidate(idx).select(this);
            }
        } catch (const std::invalid_argument &e) {
            FCITX_WARN() << "selectCandidate index out of range";
            return false;
        }
        return true;
    }

    bool selectCandidatePaged(int idx) {
        const auto &list = inputPanel().candidateList();
        if (!list) {
            return false;
        }
        try {
            list->candidate(idx).select(this);
        } catch (const std::invalid_argument &e) {
            FCITX_WARN() << "selectCandidate index out of range";
            return false;
        }
        return true;
    }

    std::vector<CandidateEntity> getCandidates(const int offset, const int limit) {
        std::vector<CandidateEntity> candidates;
        const auto &list = inputPanel().candidateList();
        if (list) {
            const int last = offset + limit;
            const auto &bulk = list->toBulk();
            if (bulk) {
                const int totalSize = bulk->totalSize();
                const int end = totalSize < 0 ? last : std::min(totalSize, last);
                for (int i = offset; i < end; i++) {
                    try {
                        candidates.emplace_back(candidateEntity(bulk->candidateFromAll(i)));
                    } catch (const std::invalid_argument &e) {
                        break;
                    }
                }
            } else {
                const int end = std::min(list->size(), last);
                for (int i = offset; i < end; i++) {
                    candidates.emplace_back(candidateEntityWithLabel(list->label(i), list->candidate(i)));
                }
            }
        }
        return candidates;
    }

    std::vector<CandidateActionEntity> getCandidateAction(const int idx) {
        std::vector<CandidateActionEntity> actions;
        const auto &list = inputPanel().candidateList();
        if (list) {
            const auto &actionable = list->toActionable();
            if (actionable) {
                if (idx >= list->size()) {
                    const auto &bulk = list->toBulk();
                    if (bulk) {
                        try {
                            const auto &c = bulk->candidateFromAll(idx);
                            for (const auto &a: actionable->candidateActions(c)) {
                                actions.emplace_back(a);
                            }
                        } catch (const std::exception &e) {
                            FCITX_WARN() << "getCandidateAction(" << idx << ") failed:" << e.what();
                        }
                    }
                } else {
                    const auto &c = list->candidate(idx);
                    for (const auto &a: actionable->candidateActions(c)) {
                        actions.emplace_back(a);
                    }
                }
            }
        }
        return actions;
    }

    void triggerCandidateAction(const int idx, const int actionIdx) {
        const auto &list = inputPanel().candidateList();
        if (!list) return;
        const auto &actionable = list->toActionable();
        if (!actionable) return;
        if (idx >= list->size()) {
            const auto &bulk = list->toBulk();
            if (bulk) {
                try {
                    const auto &c = bulk->candidateFromAll(idx);
                    actionable->triggerAction(c, actionIdx);
                } catch (const std::exception &e) {
                    FCITX_WARN() << "triggerCandidateAction(" << idx << ") failed:" << e.what();
                }
            }
        } else {
            const auto &c = list->candidate(idx);
            actionable->triggerAction(c, actionIdx);
        }
    }

    void triggerTabAction(const int idx) {
        const auto &list = inputPanel().candidateList();
        if (!list) return;
        const auto &tabbed = list->toTabbed();
        if (!tabbed) return;
        try {
            tabbed->triggerTabAction(idx);
        } catch (const std::exception &e) {
            FCITX_WARN() << "triggerTabAction(" << idx << ") failed:" << e.what();
        }
    }

    void offsetCandidatePage(int delta) {
        if (delta == 0) {
            return;
        }
        const auto &list = inputPanel().candidateList();
        if (!list) {
            return;
        }
        const auto &pageable = list->toPageable();
        if (!pageable) {
            return;
        }
        if (delta > 0 && pageable->hasNext()) {
            pageable->next();
            updateUserInterface(UserInterfaceComponent::InputPanel);
        } else if (delta < 0 && pageable->hasPrev()) {
            pageable->prev();
            updateUserInterface(UserInterfaceComponent::InputPanel);
        }
    }

private:
    AndroidFrontend *frontend_;
    int uid_;

    inline Text filterText(const Text &orig) {
        return frontend_->instance()->outputFilter(this, orig);
    }

    inline std::string filterString(const Text &orig) {
        return filterText(orig).toString();
    }

    CandidateEntity candidateEntity(const CandidateWord &candidate) {
        return CandidateEntity("", filterString(candidate.text()),
                               filterString(candidate.comment()), candidate.spaceBetweenComment());
    }

    CandidateEntity candidateEntityWithLabel(const Text &label, const CandidateWord &candidate) {
        return CandidateEntity(filterString(label), filterString(candidate.text()),
                               filterString(candidate.comment()), candidate.spaceBetweenComment());
    }
};

AndroidFrontend::AndroidFrontend(Instance *instance)
        : instance_(instance),
          focusGroup_("android", instance->inputContextManager()),
          activeIC_(nullptr),
          icCache_(),
          eventHandlers_(),
          pagingMode_(0) {
    eventHandlers_.emplace_back(instance_->watchEvent(
            EventType::InputContextInputMethodActivated,
            EventWatcherPhase::Default,
            [this](Event &event) {
                auto &e = static_cast<InputMethodActivatedEvent &>(event);
                if (e.inputContext() != activeIC_) return;
                imChangeCallback(makeInputMethodStatus(activeIC_));
            }
    ));
    eventHandlers_.emplace_back(instance_->watchEvent(
            EventType::InputContextSwitchInputMethod,
            EventWatcherPhase::Default,
            [this](Event &event) {
                auto &e = static_cast<InputContextSwitchInputMethodEvent &>(event);
                if (e.inputContext() != activeIC_) return;
                const auto &reason = static_cast<std::underlying_type<InputMethodSwitchedReason>::type>(e.reason());
                const std::string &oldIM = e.oldInputMethod();
                switchInputMethodCallback(reason, oldIM);
            }
    ));
    eventHandlers_.emplace_back(instance_->watchEvent(
            EventType::InputContextFlushUI,
            EventWatcherPhase::Default,
            [this](Event &event) {
                auto &e = static_cast<InputContextFlushUIEvent &>(event);
                if (e.inputContext() != activeIC_) return;
                switch (e.component()) {
                    case UserInterfaceComponent::InputPanel: {
                        rerankCandidates();
                        activeIC_->updateInputPanel();
                        if (pagingMode_ == 0) {
                            activeIC_->updateCandidatesBulk();
                        } else {
                            activeIC_->updateCandidatesPaged();
                        }
                        break;
                    }
                    case UserInterfaceComponent::StatusArea: {
                        statusAreaUpdateCallback(makeStatusAreaActions(activeIC_), makeInputMethodStatus(activeIC_));
                        break;
                    }
                }
            }
    ));
}

void AndroidFrontend::configureSuzu(const std::string &model, const std::string &vocab) {
    suzu_.configure(model, vocab);
}

void AndroidFrontend::setSuzuContext(const std::string &context, bool enabled, bool allowDiagnosticText) {
    if ((!enabled && suzuEnabled_) || (context.empty() && !suzuContext_.empty())) suzu_.clearContext();
    suzuContext_ = allowDiagnosticText ? context : "";
    suzuEnabled_ = enabled;
    allowDiagnosticText_ = allowDiagnosticText;
}

void AndroidFrontend::setSuzuDiagnostics(bool enabled) { diagnostics_.setEnabled(enabled); }
void AndroidFrontend::clearSuzuDiagnostics() { diagnostics_.clear(); }
std::string AndroidFrontend::suzuDiagnostics() { return diagnostics_.snapshot(suzuStatus()); }

std::string AndroidFrontend::suzuStatus() {
    auto status = suzu_.status();
    status.pop_back();
    return status + ",\"reorders\":" + std::to_string(suzuReorders_) + "}";
}

void AndroidFrontend::rerankCandidates() {
    if (!activeIC_) return;
    auto &panel = activeIC_->inputPanel();
    auto list = panel.candidateList();
    if (!list || list->empty() || rankedList_.lock() == list) return;
    const bool capture = diagnostics_.enabled() && diagnosedList_.lock() != list;
    if (capture) diagnosedList_ = list;
    RankingTrace trace;
    auto finish = [&](const std::string &reason) {
        if (capture) { trace.reason = reason; diagnostics_.add(std::move(trace)); }
    };
    const auto flags = activeIC_->capabilityFlags();
    // Includes numeric passwords and no-personalized-learning fields, even when
    // the engine doesn't expose those through its own Password capability.
    if (!allowDiagnosticText_ || flags.test(CapabilityFlag::Password) || flags.test(CapabilityFlag::Sensitive)) {
        finish("private_input"); return;
    }
    if (capture) {
        trace.input = panel.preedit().toString();
        std::vector<size_t> offsets;
        for (size_t i = 0; i < suzuContext_.size();) {
            offsets.push_back(i);
            auto c = static_cast<unsigned char>(suzuContext_[i]);
            size_t width = c < 0x80 ? 1 : c < 0xe0 ? 2 : c < 0xf0 ? 3 : 4;
            // JNI modified UTF-8 encodes supplementary characters as two
            // three-byte surrogates. Keep the pair together when trimming.
            if (i + 5 < suzuContext_.size() && c == 0xed &&
                (static_cast<unsigned char>(suzuContext_[i + 1]) & 0xf0) == 0xa0 &&
                static_cast<unsigned char>(suzuContext_[i + 3]) == 0xed &&
                (static_cast<unsigned char>(suzuContext_[i + 4]) & 0xf0) == 0xb0) width = 6;
            i += std::min(width, suzuContext_.size() - i);
        }
        trace.context = offsets.size() > 30 ? suzuContext_.substr(offsets[offsets.size() - 30]) : suzuContext_;
        const auto *bulk = list->toBulk();
        int total = bulk ? bulk->totalSize() : list->size();
        int limit = total < 0 ? 10 : std::min(10, total);
        for (int i = 0; i < limit; ++i) {
            try {
                const auto &candidate = bulk ? bulk->candidateFromAll(i) : list->candidate(i);
                RankingCandidateTrace item;
                item.text = candidate.text().toStringForCommit();
                item.originalRank = item.finalRank = i + 1;
                trace.candidates.push_back(std::move(item));
            } catch (const std::invalid_argument &) { break; }
        }
    }
    auto *entry = instance_->inputMethodEntry(activeIC_);
    if (!entry || entry->uniqueName() != "pinyin") { finish("not_pinyin"); return; }
    if (!suzuEnabled_) { finish("disabled"); return; }
    if (suzuContext_.empty()) { finish("no_context"); return; }
    // Only composing candidates. Leave prediction, punctuation and special modes alone.
    if (panel.preedit().empty()) { finish("prediction"); return; }
    if (!panel.auxUp().empty() || !panel.auxDown().empty()) { finish("special_mode"); return; }
    auto *common = dynamic_cast<CommonCandidateList *>(list.get());
    if (!common || common->currentPage() != 0 || common->totalSize() < 2 ||
        common->originSize() != static_cast<size_t>(common->totalSize())) { finish("unsupported_list"); return; }
    rankedList_ = list;
    const int count = std::min(10, common->totalSize());
    auto word = [&](int i) { return common->candidateFromAll(i).text().toStringForCommit(); };
    const auto leader = word(0);
    const auto length = utf8::lengthValidated(leader);
    if (length < 1 || length > 12) { finish("unsupported_length"); return; }
    std::vector<int> positions;
    std::vector<std::string> words;
    for (int i = 0; i < count; ++i) {
        auto text = word(i);
        if (utf8::lengthValidated(text) != length) {
            if (capture) trace.candidates[i].excluded = "different_length";
            continue;
        }
        bool chinese = true;
        for (auto cp : utf8::MakeUTF8CharRange(text)) {
            if (cp < 0x3400 || cp > 0x9fff) { chinese = false; break; }
        }
        if (chinese) { positions.push_back(i); words.push_back(std::move(text)); }
        else if (capture) trace.candidates[i].excluded = "not_chinese";
    }
    if (positions.size() < 2 || positions.front() != 0) { finish("few_comparable_candidates"); return; }
    std::vector<double> scores;
    SuzuScoreInfo info;
    const bool scored = suzu_.score(suzuContext_, words, scores, &info);
    trace.elapsedMs = info.elapsedMs;
    if (info.modelMs >= 0) trace.modelMs = info.modelMs;
    if (!scored) {
        if (capture) for (int index : info.unknownCandidates) {
            if (index >= 0 && index < static_cast<int>(positions.size())) trace.candidates[positions[index]].excluded = "out_of_vocab";
        }
        finish(info.reason); return;
    }
    // A small engine-rank prior plus a conservative 2.5-log-probability gate.
    std::vector<int> order(words.size());
    std::iota(order.begin(), order.end(), 0);
    for (double score : scores) if (!std::isfinite(score)) { finish("nonfinite_score"); return; }
    for (size_t i = 0; i < scores.size(); ++i) {
        if (capture) {
            auto &candidate = trace.candidates[positions[i]];
            candidate.rawScore = scores[i];
            candidate.rankPenalty = 0.3 * positions[i];
            candidate.finalScore = scores[i] - 0.3 * positions[i];
        }
        scores[i] -= 0.3 * positions[i];
    }
    std::stable_sort(order.begin(), order.end(), [&](int a, int b) { return scores[a] > scores[b]; });
    if (scores[order.front()] < scores[0] + 2.5) {
        trace.topProtected = order.front() != 0;
        order.erase(std::find(order.begin(), order.end(), 0));
        order.insert(order.begin(), 0);
    }
    // Reorder the engine's actual CandidateWord objects. Their select callbacks,
    // learning and actions remain intact, and all UI/keyboard paths agree.
    std::vector<const CandidateWord *> desired, current;
    for (int i = 0; i < count; ++i) current.push_back(&common->candidateFromAll(i));
    desired = current;
    for (size_t i = 0; i < positions.size(); ++i) desired[positions[i]] = current[positions[order[i]]];
    if (capture) for (size_t i = 0; i < trace.candidates.size(); ++i) {
        trace.candidates[i].finalRank = static_cast<int>(std::find(desired.begin(), desired.end(), current[i]) - desired.begin()) + 1;
    }
    bool changed = false;
    for (int i = 0; i < count; ++i) {
        auto found = std::find(current.begin() + i, current.end(), desired[i]);
        int from = static_cast<int>(found - current.begin());
        if (from == i) continue;
        common->move(from, i);
        std::rotate(current.begin() + i, current.begin() + from, current.begin() + from + 1);
        changed = true;
    }
    if (changed) ++suzuReorders_;
    trace.changed = changed;
    finish(changed ? "reordered" : "unchanged");
}

void AndroidFrontend::keyEvent(const Key &key, bool isRelease, const int timestamp) {
    if (!activeIC_) return;
    KeyEvent keyEvent(activeIC_, key, isRelease);
    activeIC_->keyEvent(keyEvent);
    if (!keyEvent.accepted()) {
        auto sym = key.sym();
        keyEventCallback(sym, key.states(), Key::keySymToUnicode(sym), isRelease, timestamp);
    }
}

void AndroidFrontend::forwardKey(const Key &key, bool isRelease) {
    auto sym = key.sym();
    keyEventCallback(sym, key.states(), Key::keySymToUnicode(sym), isRelease, -1);
}

void AndroidFrontend::commitString(const std::string &str, const int cursor) {
    commitStringCallback(str, cursor);
}

void AndroidFrontend::updateCandidateList(const std::vector<CandidateEntity> &candidates, const int size) {
    candidateListCallback(candidates, size);
}

void AndroidFrontend::updateClientPreedit(const Text &clientPreedit) {
    preeditCallback(clientPreedit);
}

void AndroidFrontend::updateInputPanel(const Text &preedit, const Text &auxUp, const Text &auxDown, const std::vector<CandidateActionEntity> &tabs) {
    inputPanelCallback(preedit, auxUp, auxDown, tabs);
}

void AndroidFrontend::releaseInputContext(const int uid) {
    icCache_.release(uid);
}

bool AndroidFrontend::selectCandidate(int idx) {
    if (!activeIC_) return false;
    if (pagingMode_) {
        return activeIC_->selectCandidatePaged(idx);
    } else {
        return activeIC_->selectCandidateBulk(idx);
    }
}

std::vector<CandidateActionEntity> AndroidFrontend::getCandidateActions(const int idx) {
    if (!activeIC_) return {};
    return activeIC_->getCandidateAction(idx);
}

void AndroidFrontend::triggerCandidateAction(const int idx, const int actionIdx) {
    if (!activeIC_) return;
    activeIC_->triggerCandidateAction(idx, actionIdx);
}

void AndroidFrontend::triggerTabAction(const int idx) {
    if (!activeIC_) return;
    activeIC_->triggerTabAction(idx);
}

bool AndroidFrontend::isInputPanelEmpty() {
    if (!activeIC_) return true;
    return activeIC_->inputPanel().empty();
}

void AndroidFrontend::resetInputContext() {
    if (!activeIC_) return;
    activeIC_->reset();
}

void AndroidFrontend::repositionCursor(int position) {
    if (!activeIC_) return;
    InvokeActionEvent event(InvokeActionEvent::Action::LeftClick, position, activeIC_);
    activeIC_->invokeAction(event);
}

void AndroidFrontend::focusInputContext(bool focus) {
    if (!activeIC_) return;
    if (focus) {
        activeIC_->focusIn();
    } else {
        setSuzuContext("", false, false);
        activeIC_->focusOut();
    }
}

void AndroidFrontend::activateInputContext(const int uid, const std::string &pkgName) {
    auto *ptr = icCache_.find(uid);
    if (ptr) {
        activeIC_ = dynamic_cast<AndroidInputContext *>(ptr->get());
    } else {
        auto *ic = new AndroidInputContext(this, instance_->inputContextManager(), uid, pkgName);
        activeIC_ = ic;
        icCache_.insert(uid, ic);
        ic->setFocusGroup(&focusGroup_);
    }
}

InputContext *AndroidFrontend::activeInputContext() const {
    return activeIC_;
}

void AndroidFrontend::deactivateInputContext(const int uid) {
    setSuzuContext("", false, false);
    auto *ptr = icCache_.find(uid);
    if (!ptr) return;
    focusGroup_.setFocusedInputContext(nullptr);
    activeIC_ = nullptr;
}

void AndroidFrontend::setCapabilityFlags(uint64_t flag) {
    if (!activeIC_) return;
    activeIC_->setCapabilityFlags(CapabilityFlags(flag));
}

void AndroidFrontend::setCandidateListCallback(const CandidateListCallback &callback) {
    candidateListCallback = callback;
}

std::vector<CandidateEntity> AndroidFrontend::getCandidates(const int offset, const int limit) {
    if (!activeIC_) return {};
    return activeIC_->getCandidates(offset, limit);
}

void AndroidFrontend::deleteSurrounding(const int before, const int after) {
    deleteSurroundingCallback(before, after);
}

void AndroidFrontend::showToast(const std::string &s) {
    toastCallback(s);
}

void AndroidFrontend::setCandidatePagingMode(const int mode) {
    pagingMode_ = mode;
    if (mode == 0) {
        activeIC_->updateCandidatesBulk();
    } else {
        activeIC_->updateCandidatesPaged();
    }
}

void AndroidFrontend::updatePagedCandidate(const PagedCandidateEntity &paged) {
    pagedCandidateCallback(paged);
}

void AndroidFrontend::offsetCandidatePage(int delta) {
    if (!activeIC_) return;
    activeIC_->offsetCandidatePage(delta);
}

void AndroidFrontend::triggerCandidateListTabAction(int id) {
    if (!activeIC_) return;
    activeIC_->triggerTabAction(id);
}

void AndroidFrontend::setCommitStringCallback(const CommitStringCallback &callback) {
    commitStringCallback = callback;
}

void AndroidFrontend::setPreeditCallback(const ClientPreeditCallback &callback) {
    preeditCallback = callback;
}

void AndroidFrontend::setInputPanelCallback(const InputPanelCallback &callback) {
    inputPanelCallback = callback;
}

void AndroidFrontend::setKeyEventCallback(const KeyEventCallback &callback) {
    keyEventCallback = callback;
}

void AndroidFrontend::setInputMethodChangeCallback(const InputMethodChangeCallback &callback) {
    imChangeCallback = callback;
}

void AndroidFrontend::setStatusAreaUpdateCallback(const StatusAreaUpdateCallback &callback) {
    statusAreaUpdateCallback = callback;
}

void AndroidFrontend::setDeleteSurroundingCallback(const DeleteSurroundingCallback &callback) {
    deleteSurroundingCallback = callback;
}

void AndroidFrontend::setToastCallback(const ToastCallback &callback) {
    toastCallback = callback;
}

void AndroidFrontend::setPagedCandidateCallback(const PagedCandidateCallback &callback) {
    pagedCandidateCallback = callback;
}

void AndroidFrontend::setSwitchInputMethodCallback(const SwitchInputMethodCallback &callback) {
    switchInputMethodCallback = callback;
}

InputMethodStatus AndroidFrontend::makeInputMethodStatus(InputContext *ic) {
    auto *entry = instance_->inputMethodEntry(ic);
    auto *engine = instance_->inputMethodEngine(ic);
    return {entry, engine, ic};
}

std::vector<ActionEntity> AndroidFrontend::makeStatusAreaActions(fcitx::InputContext *ic) {
    auto actions = std::vector<ActionEntity>();
    for (auto group: {fcitx::StatusGroup::BeforeInputMethod,
                      fcitx::StatusGroup::InputMethod,
                      fcitx::StatusGroup::AfterInputMethod}) {
        for (auto act: ic->statusArea().actions(group)) {
            actions.emplace_back(act, ic);
        }
    }
    return actions;
}

class AndroidFrontendFactory : public AddonFactory {
public:
    AddonInstance *create(AddonManager *manager) override {
        return new AndroidFrontend(manager->instance());
    }
};

} // namespace fcitx

FCITX_ADDON_FACTORY(fcitx::AndroidFrontendFactory)
