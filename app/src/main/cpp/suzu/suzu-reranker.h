// SPDX-License-Identifier: GPL-3.0-or-later
#pragma once
#include <condition_variable>
#include <cstdint>
#include <mutex>
#include <string>
#include <thread>
#include <vector>

// One model-owning worker. The Fcitx thread waits at most 40 ms and never
// applies late results to a newer candidate list. No text is logged or saved.
struct SuzuScoreInfo {
    std::string reason;
    double elapsedMs = 0;
    double modelMs = -1;
    std::vector<int> unknownCandidates;
};
class SuzuReranker {
public:
    SuzuReranker();
    ~SuzuReranker();
    void configure(const std::string &model, const std::string &vocab);
    bool score(const std::string &context, const std::vector<std::string> &candidates,
               std::vector<double> &scores, SuzuScoreInfo *info = nullptr);
    std::string status();
    void clearContext();
private:
    void run();
    std::mutex mutex_;
    std::condition_variable wake_, finished_;
    bool stopping_ = false, configure_ = false, queued_ = false, busy_ = false, clear_ = false;
    std::string state_ = "unconfigured", modelPath_, vocabPath_, context_;
    std::vector<std::string> candidates_;
    std::vector<double> result_;
    std::string resultReason_;
    std::vector<int> resultUnknown_;
    uint64_t request_ = 0, completed_ = 0, epoch_ = 0;
    unsigned scored_ = 0, timeouts_ = 0;
    double lastMs_ = 0;
    std::thread worker_;
};
