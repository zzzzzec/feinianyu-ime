// SPDX-License-Identifier: GPL-3.0-or-later
#include "typing-correction.h"
#include "pinyin.h"
#include "pinyincandidate.h"

#include <algorithm>
#include <array>
#include <chrono>
#include <cstdlib>
#include <fcitx-utils/utf8.h>
#include <libime/core/segmentgraph.h>
#include <libime/pinyin/pinyindata.h>
#include <libime/pinyin/pinyindecoder.h>
#include <libime/pinyin/pinyinime.h>
#include <memory>
#include <ranges>
#include <string>
#include <string_view>
#include <utility>
#include <vector>

namespace fcitx {
namespace {

constexpr size_t maxInputLength = 36;
constexpr size_t maxDecodes = 12;
constexpr size_t maxCandidates = 3;
constexpr auto searchBudget = std::chrono::milliseconds(8);

bool isChinese(std::string_view text) {
  for (const auto codepoint : utf8::MakeUTF8CharRange(text)) {
    if (codepoint < 0x3400 || codepoint > 0x9fff)
      return false;
  }
  return !text.empty();
}

bool isFullPinyin(std::string_view input) {
  std::array<bool, maxInputLength + 1> reachable{};
  reachable[0] = true;
  const auto &map = libime::getPinyinMapV2();
  for (size_t start = 0; start < input.size(); ++start) {
    if (!reachable[start])
      continue;
    for (size_t length = 1; length <= std::min<size_t>(6, input.size() - start);
         ++length) {
      const auto [first, last] = map.equal_range(input.substr(start, length));
      if (std::any_of(first, last, [](const libime::PinyinEntry &entry) {
            return entry.flags() == libime::PinyinFuzzyFlag::None &&
                   entry.final() != libime::PinyinFinal::Zero;
          })) {
        reachable[start + length] = true;
      }
    }
  }
  return reachable[input.size()];
}

bool covers(const libime::SentenceResult &candidate, size_t inputLength) {
  return !candidate.sentence().empty() &&
         candidate.sentence().back()->to()->index() == inputLength;
}

std::string fullPinyin(const libime::PinyinContext &context,
                       const libime::SentenceResult &candidate) {
  auto pinyin = context.candidateFullPinyin(candidate);
  std::erase(pinyin, '\'');
  return pinyin;
}

struct Correction {
  std::string text;
  std::string encodedPinyin;
  float score;
};

class TypingCorrectionCandidate final : public CandidateWord {
public:
  TypingCorrectionCandidate(PinyinEngine &engine,
                            const libime::PinyinContext &context,
                            Correction correction)
      : CandidateWord(Text(std::move(correction.text))), engine_(engine),
        input_(context.userInput()), start_(context.selectedLength()),
        encodedPinyin_(std::move(correction.encodedPinyin)) {}

  void select(InputContext *inputContext) const override {
    auto *state = inputContext->propertyFor(&engine_.factory());
    auto &context = state->context_;
    if (state->mode_ != PinyinMode::Normal || context.userInput() != input_ ||
        context.selectedLength() != start_ ||
        context.cursor() != context.size()) {
      return;
    }
    context.selectCustom(input_.size() - start_, text().toString(),
                         encodedPinyin_);
    engine_.updateUI(inputContext);
  }

private:
  PinyinEngine &engine_;
  std::string input_;
  size_t start_;
  std::string encodedPinyin_;
};

} // namespace

void addTypingCorrections(PinyinEngine &engine, InputContext &inputContext,
                          const libime::PinyinContext &context,
                          const std::shared_ptr<CandidateList> &candidates) {
  if (!context.ime()->fuzzyFlags().test(libime::PinyinFuzzyFlag::Correction) ||
      context.useShuangpin() || context.cursor() != context.size() ||
      inputContext.capabilityFlags().testAny(
          CapabilityFlag::PasswordOrSensitive) ||
      inputContext.propertyFor(&engine.factory())->mode_ !=
          PinyinMode::Normal) {
    return;
  }

  const auto raw =
      std::string_view(context.userInput()).substr(context.selectedLength());
  if (raw.size() < 4 || raw.size() > maxInputLength ||
      !std::ranges::all_of(raw,
                           [](char key) { return key >= 'a' && key <= 'z'; })) {
    return;
  }

  auto &list = static_cast<CommonCandidateList &>(*candidates);
  bool keepFirst = !list.empty();
  if (keepFirst) {
    const auto *first =
        dynamic_cast<const PinyinCandidateWord *>(&list.candidateFromAll(0));
    if (first && first->candidateIndex() >= 0) {
      const auto &candidate =
          context.candidatesToCursor().at(first->candidateIndex());
      const bool chinese = isChinese(candidate.toString());
      if (chinese && covers(candidate, raw.size()) &&
          fullPinyin(context, candidate) == raw) {
        return;
      }
      keepFirst = chinese;
    }
  }

  bool swapKeys = true;
  bool dropKey = true;
#ifndef NDEBUG
  // Process-local controls for replaying either edit independently.
  if (const auto *mode = std::getenv("FEINIANYU_PINYIN_EDITS")) {
    swapKeys = std::string_view(mode) == "swap";
    dropKey = std::string_view(mode) == "extra";
    if (!swapKeys && !dropKey)
      return;
  }
#endif

  const auto deadline = std::chrono::steady_clock::now() + searchBudget;
  std::vector<Correction> results;
  size_t decodes = 0;
  libime::PinyinContext probe(context.ime());
  auto contextWords = context.contextWordsWithPinyin();
  auto selected = context.selectedWordsWithPinyin();
  contextWords.insert(contextWords.end(), selected.begin(), selected.end());
  probe.setContextWordsWithPinyin(contextWords);

  const auto tryInput = [&](const std::string &input) {
    if (decodes >= maxDecodes || std::chrono::steady_clock::now() >= deadline)
      return false;
    if (!isFullPinyin(input))
      return true;
    ++decodes;
    probe.clear();
    if (!probe.type(input))
      return true;
    for (const auto &candidate : probe.candidates() | std::views::take(3)) {
      if (!covers(candidate, input.size()) ||
          fullPinyin(probe, candidate) != input)
        continue;
      auto text = candidate.toString();
      if (!isChinese(text) || context.candidatesToCursorSet().contains(text))
        continue;
      if (std::ranges::any_of(results, [&](const Correction &item) {
            return item.text == text;
          }))
        continue;
      std::string encoded;
      for (const auto *word : candidate.sentence()) {
        encoded += word->as<libime::PinyinLatticeNode>().encodedPinyin();
      }
      results.push_back(
          {std::move(text), std::move(encoded), candidate.score()});
    }
    return true;
  };

  // A repeated key is the strongest insertion signal. Scan each run once.
  if (dropKey) {
    for (size_t i = 1; i < raw.size(); ++i) {
      if (raw[i] != raw[i - 1] || (i >= 2 && raw[i] == raw[i - 2]))
        continue;
      auto input = std::string(raw);
      input.erase(i, 1);
      if (!tryInput(input))
        break;
    }
  }
  if (swapKeys) {
    for (size_t i = 1; i < raw.size(); ++i) {
      if (raw[i - 1] == raw[i])
        continue;
      auto input = std::string(raw);
      std::swap(input[i - 1], input[i]);
      if (!tryInput(input))
        break;
    }
  }
  if (dropKey) {
    for (size_t i = 0; i < raw.size(); ++i) {
      if ((i && raw[i - 1] == raw[i]) ||
          (i + 1 < raw.size() && raw[i] == raw[i + 1]))
        continue;
      auto input = std::string(raw);
      input.erase(i, 1);
      if (!tryInput(input))
        break;
    }
  }

  std::stable_sort(results.begin(), results.end(),
                   [](const Correction &a, const Correction &b) {
                     return a.score > b.score;
                   });
  int position = keepFirst ? 1 : 0;
  for (auto &result : results | std::views::take(maxCandidates)) {
    list.insert(position++, std::make_unique<TypingCorrectionCandidate>(
                                engine, context, std::move(result)));
  }
  if (keepFirst && position > 1) {
    if (auto *slots = inputContext.propertyAs<SupplementalCorrectionSlots>(
            SupplementalCorrectionSlots::name)) {
      slots->list = candidates;
      slots->count = position - 1;
    }
  }
}
} // namespace fcitx
