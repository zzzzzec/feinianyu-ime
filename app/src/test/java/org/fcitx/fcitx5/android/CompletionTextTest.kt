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
}
