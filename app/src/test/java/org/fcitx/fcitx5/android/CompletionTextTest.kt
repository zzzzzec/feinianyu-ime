// SPDX-License-Identifier: GPL-3.0-or-later
package org.fcitx.fcitx5.android

import org.fcitx.fcitx5.android.completion.CompletionText
import org.junit.Assert.*
import org.junit.Test

class CompletionTextTest {
    @Test fun contextWindowPreservesUnicodeAndWhitespace() {
        assertEquals("😀北京", CompletionText.suffix("前文😀北京", 3))
        assertEquals("北京 ", CompletionText.suffix("前文北京 ", 3))
        assertEquals("短句", CompletionText.suffix("短句", 128))
    }
    @Test fun controlTokensAndInvalidTextNeverReachEditor() {
        assertEquals("可以聊一下。", CompletionText.suggestion("可以聊一下。\n另一段"))
        assertEquals("好的", CompletionText.suggestion("好的<|im_end|>"))
        assertEquals("", CompletionText.suggestion("\uFFFD"))
        assertEquals("", CompletionText.suggestion("文本\u0000"))
        assertEquals("一😀二", CompletionText.suggestion("一😀二三四", 3))
        assertEquals(" status", CompletionText.suggestion(" status "))
    }
    @Test fun multipleOptionsAreSanitizedDeduplicatedAndBounded() {
        assertEquals(listOf("休息吧，", "睡个好觉。", "明天见。"), CompletionText.suggestions(listOf(
            "休息吧，\n另一段", "休息吧，", "\uFFFD", "睡个好觉。", "明天见。", "第四个")))
        assertEquals(listOf(" status", " diff"), CompletionText.suggestions(listOf(" status ", " diff")))
    }
    @Test fun escapedParagraphsAndLongOutputsStayOutOfShortCompletions() {
        assertEquals("", CompletionText.suggestion("\\n\\n另一段"))
        assertEquals("你好", CompletionText.suggestion("你好\\n下一段"))
        assertEquals("一二三四五六七八", CompletionText.suggestion("一二三四五六七八九十"))
        assertEquals(emptyList<String>(), CompletionText.suggestions(listOf("\\n", "\n", "")))
        assertEquals(listOf("好"), CompletionText.suggestions(listOf("好"))) // Native confidence controls single-character offers.
    }
}
