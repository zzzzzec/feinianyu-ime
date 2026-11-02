// SPDX-License-Identifier: GPL-3.0-or-later
package org.fcitx.fcitx5.android

import org.fcitx.fcitx5.android.completion.CompletionCandidates
import org.fcitx.fcitx5.android.core.CandidateWord
import org.junit.Assert.*
import org.junit.Test

class CompletionCandidatesTest {
    private fun word(text: String) = CandidateWord("", text, "")

    @Test fun aiComesFirstAndDeduplicationKeepsOriginalSelectionIndices() {
        val merged = CompletionCandidates.merge(arrayOf(word("继续"), word("接着")),
            arrayOf(word("好的"), word("继续"), word("明天")))
        assertEquals(listOf("继续", "接着", "好的", "明天"), merged.map { it.word.text })
        assertEquals(listOf(0, 1, null, null), merged.map { it.aiIndex })
        assertEquals(listOf(null, null, 0, 2), merged.map { it.nativeIndex })
    }

    @Test fun absentAiLeavesNativeFallbackAvailable() {
        val native = arrayOf(word("世界"), word("朋友"))
        val merged = CompletionCandidates.merge(emptyArray(), native)
        assertEquals(native.toList(), merged.map { it.word })
        assertEquals(listOf(0, 1), merged.map { it.nativeIndex })
        assertTrue(merged.all { it.aiIndex == null })
    }

    @Test fun expandedTailAndAiIndicesSurviveDuplicatesAndEmptyItems() {
        val native = Array(100) { word("原生$it") }
        val merged = CompletionCandidates.merge(arrayOf(word(""), word("原生90"), word("新续写")), native)
        assertEquals(101, merged.size)
        assertEquals(1, merged.first().aiIndex)
        assertEquals(2, merged[1].aiIndex)
        assertEquals(99, merged.last().nativeIndex)
        assertEquals("原生99", merged.last().word.text)
        assertEquals(1, merged.count { it.word.text == "原生90" })
    }
}
