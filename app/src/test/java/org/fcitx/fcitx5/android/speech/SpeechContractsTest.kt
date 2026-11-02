// SPDX-License-Identifier: GPL-3.0-or-later
package org.fcitx.fcitx5.android.speech

import org.junit.Assert.*
import org.junit.Test

class SpeechContractsTest {
    @Test fun senseVoiceMarkersNeverReachTheEditor() {
        assertEquals("今天下午开会。", SpeechTranscript.clean("<|zh|><|NEUTRAL|><|Speech|><|withitn|>今天下午开会。"))
        assertEquals("", SpeechTranscript.clean("<|nospeech|><|NEUTRAL|>"))
    }
    @Test fun segmentJoiningPreservesChineseAndEnglishBoundaries() {
        assertEquals("今天下午开会", SpeechTranscript.join("今天下午", "开会"))
        assertEquals("hello world", SpeechTranscript.join("hello", "world"))
        assertEquals("你好，world", SpeechTranscript.join("你好，", "world"))
    }
    @Test fun lateResultsCannotFollowAnEditorOrCursorChange() {
        val guard = SpeechEditorGuard()
        guard.begin(10, 4, 4)
        assertTrue(guard.accepts(10, 4, 4))
        assertFalse(guard.accepts(11, 4, 4))
        assertFalse(guard.accepts(10, 8, 8))
        guard.committed(8, 8)
        assertTrue(guard.accepts(10, 8, 8))
        guard.invalidate()
        assertFalse(guard.accepts(10, 8, 8))
    }
}
