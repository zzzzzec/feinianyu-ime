// SPDX-License-Identifier: GPL-3.0-or-later
#pragma once
#include <cstdint>
#include <deque>
#include <optional>
#include <string>
#include <vector>

struct RankingCandidateTrace {
    std::string text, excluded;
    int originalRank = 0, finalRank = 0;
    std::optional<double> rawScore, rankPenalty, finalScore;
};
struct RankingTrace {
    uint64_t id = 0;
    int64_t timestampMs = 0;
    std::string input, context, reason;
    double elapsedMs = 0;
    std::optional<double> modelMs;
    bool changed = false, topProtected = false;
    std::vector<RankingCandidateTrace> candidates;
};

// Accessed only on the Fcitx thread. Never writes text to logcat or disk.
class RankingDiagnostics {
public:
    static constexpr size_t capacity = 50;
    void setEnabled(bool enabled) { enabled_ = enabled; }
    bool enabled() const { return enabled_; }
    void clear() { records_.clear(); }
    void add(RankingTrace trace);
    std::string snapshot(const std::string &runtime) const;
private:
    bool enabled_ = false;
    uint64_t sequence_ = 0;
    std::deque<RankingTrace> records_;
};
