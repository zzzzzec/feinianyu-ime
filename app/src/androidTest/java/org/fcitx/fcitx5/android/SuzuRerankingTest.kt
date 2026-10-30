// SPDX-License-Identifier: GPL-3.0-or-later
package org.fcitx.fcitx5.android

import android.text.InputType
import android.view.inputmethod.EditorInfo
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import org.fcitx.fcitx5.android.core.*
import org.fcitx.fcitx5.android.input.SuzuContextPolicy
import org.fcitx.fcitx5.android.diagnostics.RankingDiagnosticsExport
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
            assertFalse(JSONObject(fcitx.suzuDiagnostics()).getBoolean("enabled"))
            fcitx.setSuzuDiagnostics(true)

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
            var verifiedScores = 0
            for ((prefix, code) in cases) {
                val original = candidates(prefix, code, false)
                val ranked = candidates(prefix, code, true)
                assertTrue("No candidates for $code", original.size >= 2)
                assertEquals("Candidate pool changed for $code", original.sorted(), ranked.sorted())
                if (original != ranked) changed++
                val snapshot = JSONObject(fcitx.suzuDiagnostics())
                val traces = snapshot.getJSONArray("records")
                val trace = traces.getJSONObject(traces.length() - 1)
                assertEquals(prefix, trace.getString("context"))
                val rows = trace.getJSONArray("candidates")
                val ordered = (0 until rows.length()).map { rows.getJSONObject(it) }.sortedBy { it.getInt("final_rank") }
                assertEquals("Trace disagrees with actual keyboard candidates", ranked.take(ordered.size), ordered.map { it.getString("text") })
                for (row in ordered) if (!row.isNull("raw_score")) {
                    assertEquals(row.getDouble("raw_score") - row.getDouble("rank_penalty"), row.getDouble("final_score"), 0.00001)
                    verifiedScores++
                }
                println("SUZU_CASE $code original=${original.take(5)} ranked=${ranked.take(5)}")
            }
            val status = JSONObject(fcitx.suzuStatus())
            println("SUZU_STATUS $status changed=$changed")
            assertTrue("No real model scores completed", status.getInt("scored") > 0)
            assertTrue("Model never changed a candidate list", changed > 0)
            assertTrue("Diagnostics did not capture real scores", verifiedScores > 0)

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

            fun last(snapshot: String): JSONObject = JSONObject(snapshot).getJSONArray("records").let { it.getJSONObject(it.length() - 1) }
            assertEquals("no_context", last(fcitx.suzuDiagnostics()).getString("reason"))
            val special = "上文\"反斜线\\换行\n表情😀"
            candidates(special, "nihao", false)
            val exported = JSONObject(RankingDiagnosticsExport.create(fcitx.suzuDiagnostics()))
            assertEquals(BuildConfig.APPLICATION_ID, exported.getString("app_id"))
            assertEquals(special, last(exported.getJSONObject("diagnostics").toString()).getString("context"))
            candidates("前缀😀" + "文".repeat(29), "nihao", false)
            assertEquals("😀" + "文".repeat(29), last(fcitx.suzuDiagnostics()).getString("context"))

            fcitx.clearSuzuDiagnostics()
            fcitx.reset()
            fcitx.setSuzuContext("private-secret", true, false)
            fcitx.sendKey('n'); delay(100)
            val privateSnapshot = fcitx.suzuDiagnostics()
            assertFalse(privateSnapshot.contains("private-secret"))
            val privateRecord = last(privateSnapshot)
            assertEquals("private_input", privateRecord.getString("reason"))
            assertEquals("", privateRecord.getString("input"))
            assertEquals("", privateRecord.getString("context"))
            assertEquals(0, privateRecord.getJSONArray("candidates").length())
            fcitx.setCapFlags(CapabilityFlags(CapabilityFlag.Password))
            candidates("another-secret", "nihao", true)
            assertEquals("private_input", last(fcitx.suzuDiagnostics()).getString("reason"))
            assertFalse(fcitx.suzuDiagnostics().contains("another-secret"))
            fcitx.setCapFlags(CapabilityFlags.DefaultFlags)

            fcitx.clearSuzuDiagnostics()
            fcitx.setSuzuContext("", false)
            repeat(65) { fcitx.reset(); fcitx.sendKey('n'); delay(8) }
            assertEquals(50, JSONObject(fcitx.suzuDiagnostics()).getJSONArray("records").length())
            fcitx.setSuzuDiagnostics(false)
            repeat(3) { fcitx.reset(); fcitx.sendKey('h'); delay(20) }
            assertEquals(50, JSONObject(fcitx.suzuDiagnostics()).getJSONArray("records").length())
            fcitx.clearSuzuDiagnostics()
            assertEquals(0, JSONObject(fcitx.suzuDiagnostics()).getJSONArray("records").length())
        } finally {
            fcitx.setSuzuContext("", false)
            fcitx.stop()
            scope.cancel()
        }
    }
}
