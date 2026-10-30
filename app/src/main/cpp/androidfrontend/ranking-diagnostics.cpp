// SPDX-License-Identifier: GPL-3.0-or-later
#include "ranking-diagnostics.h"
#include <chrono>
#include <cmath>
#include <iomanip>
#include <locale>
#include <sstream>

namespace {
// ASCII-only JSON also handles JNI's modified UTF-8 surrogate pairs and normal
// four-byte UTF-8 emoji, without passing either encoding to NewStringUTF.
std::string jsonString(const std::string &text) {
    std::ostringstream out;
    out << '"';
    auto unit = [&](uint32_t cp) { out << "\\u" << std::hex << std::setw(4) << std::setfill('0') << cp << std::dec; };
    for (size_t i = 0; i < text.size();) {
        auto first = static_cast<unsigned char>(text[i]);
        if (first < 0x80) {
            if (first == '"' || first == '\\') out << '\\' << static_cast<char>(first);
            else if (first < 0x20) unit(first);
            else out << static_cast<char>(first);
            ++i; continue;
        }
        int length = first >= 0xf0 && first <= 0xf4 ? 4 : first >= 0xe0 && first <= 0xef ? 3 : first >= 0xc0 && first <= 0xdf ? 2 : 0;
        uint32_t cp = length ? first & ((1 << (7 - length)) - 1) : 0xfffd;
        bool valid = length && i + length <= text.size();
        for (int j = 1; valid && j < length; ++j) {
            auto next = static_cast<unsigned char>(text[i + j]);
            valid = (next & 0xc0) == 0x80;
            cp = (cp << 6) | (next & 0x3f);
        }
        if (!valid || cp > 0x10ffff) { unit(0xfffd); ++i; continue; }
        if (cp > 0xffff) { cp -= 0x10000; unit(0xd800 + (cp >> 10)); unit(0xdc00 + (cp & 0x3ff)); }
        else unit(cp);
        i += length;
    }
    out << '"';
    return out.str();
}
void number(std::ostream &out, const std::optional<double> &value) {
    if (value && std::isfinite(*value)) out << *value;
    else out << "null";
}
}

void RankingDiagnostics::add(RankingTrace trace) {
    if (!enabled_) return;
    trace.id = ++sequence_;
    trace.timestampMs = std::chrono::duration_cast<std::chrono::milliseconds>(std::chrono::system_clock::now().time_since_epoch()).count();
    if (records_.size() == capacity) records_.pop_front();
    records_.push_back(std::move(trace));
}
std::string RankingDiagnostics::snapshot(const std::string &runtime) const {
    std::ostringstream out;
    out.imbue(std::locale::classic());
    out << std::setprecision(10) << "{\"schema_version\":1,\"enabled\":" << (enabled_ ? "true" : "false")
        << ",\"capacity\":" << capacity << ",\"runtime\":" << runtime
        << ",\"policy\":{\"candidate_limit\":10,\"context_limit\":30,\"rank_prior\":0.3,\"top_gate\":2.5},\"records\":[";
    bool first = true;
    for (const auto &record : records_) {
        if (!first) out << ',';
        first = false;
        out << "{\"id\":" << record.id << ",\"timestamp_ms\":" << record.timestampMs
            << ",\"input\":" << jsonString(record.input) << ",\"context\":" << jsonString(record.context)
            << ",\"reason\":" << jsonString(record.reason) << ",\"elapsed_ms\":" << record.elapsedMs
            << ",\"model_ms\":";
        number(out, record.modelMs);
        out << ",\"changed\":" << (record.changed ? "true" : "false")
            << ",\"top_protected\":" << (record.topProtected ? "true" : "false") << ",\"candidates\":[";
        bool firstCandidate = true;
        for (const auto &candidate : record.candidates) {
            if (!firstCandidate) out << ',';
            firstCandidate = false;
            out << "{\"text\":" << jsonString(candidate.text) << ",\"original_rank\":" << candidate.originalRank
                << ",\"final_rank\":" << candidate.finalRank << ",\"excluded\":" << jsonString(candidate.excluded)
                << ",\"raw_score\":";
            number(out, candidate.rawScore);
            out << ",\"rank_penalty\":"; number(out, candidate.rankPenalty);
            out << ",\"final_score\":"; number(out, candidate.finalScore);
            out << '}';
        }
        out << "]}";
    }
    return out.str() + "]}";
}
