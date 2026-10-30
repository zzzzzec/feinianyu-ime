// SPDX-License-Identifier: GPL-3.0-or-later
package org.fcitx.fcitx5.android

import android.text.InputType
import android.view.inputmethod.EditorInfo
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import org.fcitx.fcitx5.android.core.*
import org.fcitx.fcitx5.android.input.SuzuContextPolicy
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.ConcurrentLinkedQueue

class SuzuRerankingTest {
    @Test
    fun sensitiveEditorsDoNotProvideContext() {
        assertFalse(SuzuContextPolicy.allows(null))
        for (type in listOf(InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD,
            InputType.TYPE_CLASS_PHONE, InputType.TYPE_NULL,
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD,
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD,
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD)) {
            assertFalse(SuzuContextPolicy.allows(EditorInfo().apply { inputType = type }))
        }
        assertFalse(SuzuContextPolicy.allows(EditorInfo().apply {
            inputType = InputType.TYPE_CLASS_TEXT
            imeOptions = EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
        }))
        assertTrue(SuzuContextPolicy.allows(EditorInfo().apply { inputType = InputType.TYPE_CLASS_TEXT }))
    }

    @Test
    fun modelRanksRealFcitxCandidatesAndSelectionAgrees(): Unit = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val commits = ConcurrentLinkedQueue<String>()
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val fcitx = Fcitx(context)
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            fcitx.eventFlow.collect { if (it is FcitxEvent.CommitStringEvent) commits.add(it.data.text) }
        }
        try {
            fcitx.start()
            withTimeout(30000) { while (!fcitx.isReady) delay(50) }
            fcitx.activate(23456, "org.fcitx.suzu.synthetic-test")
            fcitx.setCapFlags(CapabilityFlags.DefaultFlags)
            fcitx.focus(true)
            fcitx.setEnabledIme(arrayOf("pinyin"))
            fcitx.activateIme("pinyin")
            val files = withContext(Dispatchers.IO) { SuzuAssets.prepare(context) }
            fcitx.configureSuzu(files.first, files.second)
            withTimeout(30000) {
                while (JSONObject(fcitx.suzuStatus()).getString("state") == "loading") delay(100)
            }
            assertEquals("ready", JSONObject(fcitx.suzuStatus()).getString("state"))

            suspend fun candidates(prefix: String, code: String, enabled: Boolean): List<String> {
                fcitx.reset()
                fcitx.setSuzuContext(prefix, enabled)
                for (key in code) { fcitx.sendKey(key); delay(55) }
                delay(100)
                return fcitx.getCandidates(0, 16).map { it.text }
            }
            val cases = listOf(
                "在森林里看到一只" to "xiaolu",
                "这项投资应该保持" to "wenjian",
                "请把钱存进" to "yinhang",
                "我今天没有足够的" to "jingli",
                "这个产品的质量需要" to "baozheng",
                "明天早上我们在车站" to "jihe",
                "这条道路上的车辆正在" to "xingshi",
                "老师要求我们提高工作" to "xiaolv"
            )
            var changed = 0
            for ((prefix, code) in cases) {
                val original = candidates(prefix, code, false)
                val ranked = candidates(prefix, code, true)
                assertTrue("No candidates for $code", original.size >= 2)
                assertEquals("Candidate pool changed for $code", original.sorted(), ranked.sorted())
                if (original != ranked) changed++
                println("SUZU_CASE $code original=${original.take(5)} ranked=${ranked.take(5)}")
            }
            val status = JSONObject(fcitx.suzuStatus())
            println("SUZU_STATUS $status changed=$changed")
            assertTrue("No real model scores completed", status.getInt("scored") > 0)
            assertTrue("Model never changed a candidate list", changed > 0)

            // Check both a touch-style explicit selection and the native space key.
            for (method in listOf("click", "space", "number")) {
                val ranked = candidates("在森林里看到一只", "xiaolu", true)
                val selected = if (method == "space") 0 else 1
                commits.clear()
                when (method) {
                    "space" -> fcitx.sendKey(' ')
                    "number" -> fcitx.sendKey('2')
                    else -> assertTrue(fcitx.select(selected))
                }
                withTimeout(5000) { while (commits.isEmpty()) delay(20) }
                assertEquals("Wrong word committed after ranking", ranked[selected], commits.poll())
            }
            // Empty context and the explicit off switch preserve the original pool/order.
            val disabled = candidates("", "nihao", false)
            assertEquals(disabled, candidates("", "nihao", true))
        } finally {
            fcitx.setSuzuContext("", false)
            fcitx.stop()
            scope.cancel()
        }
    }
}
