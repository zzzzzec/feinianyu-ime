// SPDX-License-Identifier: GPL-3.0-or-later
#pragma once

#include <fcitx/inputcontextproperty.h>
#include <memory>

namespace libime {
class PinyinContext;
}

namespace fcitx {
class CandidateList;
class InputContext;
class PinyinEngine;

struct SupplementalCorrectionSlots : InputContextProperty {
  static constexpr auto name = "feinianyuSupplementalCorrections";
  // Backups are inserted consecutively after the original first candidate.
  std::weak_ptr<CandidateList> list;
  int count = 0;
};

void addTypingCorrections(PinyinEngine &engine, InputContext &inputContext,
                          const libime::PinyinContext &context,
                          const std::shared_ptr<CandidateList> &candidates);
} // namespace fcitx
