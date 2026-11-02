// SPDX-License-Identifier: GPL-3.0-or-later
package org.fcitx.fcitx5.android.completion

import org.fcitx.fcitx5.android.core.CandidateWord

/** Preserve the source index: a displayed position is never an engine selection index. */
data class CompletionCandidate(val word: CandidateWord, val aiIndex: Int? = null, val nativeIndex: Int? = null)

object CompletionCandidates {
    fun merge(ai: Array<CandidateWord>, native: Array<CandidateWord>): List<CompletionCandidate> {
        val seen = HashSet<String>()
        return buildList {
            ai.forEachIndexed { index, word ->
                if (word.text.isNotBlank() && seen.add(word.text)) add(CompletionCandidate(word, aiIndex = index))
            }
            native.forEachIndexed { index, word ->
                if (word.text.isNotBlank() && seen.add(word.text)) add(CompletionCandidate(word, nativeIndex = index))
            }
        }
    }
}
