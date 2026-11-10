// SPDX-License-Identifier: GPL-3.0-or-later
package org.fcitx.fcitx5.android

import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.fcitx.fcitx5.android.core.CapabilityFlags
import org.fcitx.fcitx5.android.core.Fcitx
import org.fcitx.fcitx5.android.core.FcitxEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TypingCorrectionTest {
    @Test
    fun selectsBothCorrectionsAndConsumesTheOriginalInput() = runBlocking {
        val fcitx = Fcitx(InstrumentationRegistry.getInstrumentation().targetContext)
        var commit = CompletableDeferred<String>()
        val listener = launch(start = CoroutineStart.UNDISPATCHED) {
            fcitx.eventFlow.collect { event ->
                if (event is FcitxEvent.CommitStringEvent) commit.complete(event.data.text)
            }
        }
        try {
            fcitx.start()
            withTimeout(30_000) { while (!fcitx.isReady) delay(50) }
            fcitx.activate(23459, "dev.feinianyu.synthetic.correction")
            fcitx.setCapFlags(CapabilityFlags.DefaultFlags)
            fcitx.focus(true)
            fcitx.setEnabledIme(arrayOf("pinyin"))
            fcitx.activateIme("pinyin")
            fcitx.setSuzuContext("", false)

            suspend fun type(input: String) {
                fcitx.reset()
                commit = CompletableDeferred()
                for (key in input) fcitx.sendKey(key)
            }

            suspend fun select(text: String) {
                val index = fcitx.getCandidates(0, 16).indexOfFirst { it.text == text }
                assertTrue("Missing candidate: $text", index >= 0)
                assertTrue(fcitx.select(index))
            }

            type("weixin")
            assertEquals("微信", fcitx.getCandidates(0, 1).single().text)

            type("wexiin")
            select("微信")
            assertEquals("微信", withTimeout(5_000) { commit.await() })
            assertTrue(fcitx.clientPreeditCached.isEmpty())

            type("nihaoweixiin")
            select("你好")
            assertTrue("Selecting the prefix must leave the remaining pinyin", !commit.isCompleted)
            select("微信")
            assertEquals("你好微信", withTimeout(5_000) { commit.await() })
            assertTrue(fcitx.clientPreeditCached.isEmpty())
        } finally {
            listener.cancel()
            if (fcitx.isReady) fcitx.stop()
        }
    }
}
