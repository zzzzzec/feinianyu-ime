// SPDX-License-Identifier: GPL-3.0-or-later
package org.fcitx.fcitx5.android

import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.fcitx.fcitx5.android.completion.*
import org.fcitx.fcitx5.android.core.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class QwenCompletionTest {
    private val app get() = InstrumentationRegistry.getInstrumentation().targetContext
    // Exercise all decoder branches/cache mechanics independently from production confidence gates.
    private suspend fun generate(prefix: String, id: Long, budgetMs: Int) = QwenCompletionEngine.generate(
        app, prefix, id, budgetMs, CompletionPolicy("test_permissive", 0.0, 0.0, 0.0, 12, 1))
    @Test fun realBaseModelGeneratesAndCacheIsIsolated(): Unit = runBlocking {
        if (QwenModelStore.readyFile(app) == null) {
            QwenModelStore.installVerified(app, File(app.getExternalFilesDir("completion-downloads"), "qa-model.gguf"))
        }
        QwenCompletionEngine.ensureLoaded(app)
        withTimeout(120000) { while (QwenCompletionEngine.state.value !in listOf("ready", "error")) delay(100) }
        assertEquals("ready", QwenCompletionEngine.state.value)
        val outputs = JSONArray()
        val cases = listOf(
            "明天下午我有空，我们可以",
            "这份文件我看过了，有几个地方需要",
            "今天辛苦了，早点",
            "我不是不同意这个方案，只是担心现在上线会影响老用户，所以我建议",
            "请把修改后的文件发到我的",
            "会议时间改到明天下午三点，请大家"
        )
        var nonempty = 0
        for (prefix in cases) {
            val result = generate(prefix, QwenCompletionEngine.nextId(), 30000)
            val raw = result.getJSONArray("candidates")
            val options = CompletionText.suggestions((0 until raw.length()).map(raw::getString))
            if (options.size == 3) nonempty++
            assertEquals(result.toString(), "generated", result.getString("state"))
            assertEquals(result.toString(), 3, options.size)
            options.forEach { assertFalse(it.contains("<|")) }
            outputs.put(JSONObject().put("prefix", prefix).put("suggestions", JSONArray(options)).put("metrics", result))
        }
        assertTrue("Most synthetic prefixes should produce a usable short continuation", nonempty >= 4)
        val first = generate(cases[0], QwenCompletionEngine.nextId(), 30000)
        val repeat = generate(cases[0], QwenCompletionEngine.nextId(), 30000)
        assertEquals(first.getJSONArray("candidates").toString(), repeat.getJSONArray("candidates").toString())
        assertEquals(repeat.getInt("prompt_tokens"), repeat.getInt("reused_tokens"))
        val cancelledId = QwenCompletionEngine.nextId()
        QwenCompletionEngine.cancel(cancelledId)
        assertEquals("cancelled", generate(cases[0], cancelledId, 30000).getString("state"))
        assertEquals("timeout", generate("不同的长前文".repeat(20), QwenCompletionEngine.nextId(), 1).getString("state"))
        QwenNative.reset()
        val reset = generate(cases[0], QwenCompletionEngine.nextId(), 30000)
        assertEquals(0, reset.getInt("reused_tokens"))
        File(app.filesDir, "qwen-synthetic-results.json").writeText(outputs.toString(2))
        val exported = CompletionPerf.export(app)
        assertFalse(exported.contains(cases[0]))
        outputs.getJSONObject(0).getJSONArray("suggestions").let { options ->
            for (index in 0 until options.length()) assertFalse("Performance records must not contain any option text", exported.contains(options.getString(index)))
        }
        File(app.filesDir, "qwen-perf-export.json").writeText(exported)
    }
    @Test fun builtinPinyinPredictionIsOffAndCannotBeReenabled(): Unit = runBlocking {
        val fcitx = Fcitx(app)
        try {
            fcitx.start()
            withTimeout(30000) { while (!fcitx.isReady) delay(50) }
            fcitx.activate(24131, "dev.feinianyu.completion.synthetic")
            fcitx.setCapFlags(CapabilityFlags.DefaultFlags); fcitx.focus(true)
            fcitx.setEnabledIme(arrayOf("pinyin")); fcitx.activateIme("pinyin")
            val config = fcitx.getAddonConfig("pinyin")["cfg"]
            config.getOrCreate("Prediction").value = "True"
            fcitx.setAddonConfig("pinyin", config)
            assertEquals("False", fcitx.getAddonConfig("pinyin")["cfg"]["Prediction"].value)
            for (key in "nihao") fcitx.sendKey(key)
            assertTrue(fcitx.getCandidates(0, 5).isNotEmpty())
            fcitx.select(0); delay(100)
            assertTrue("Old post-commit predictions must be absent", fcitx.getCandidates(0, 5).isEmpty())
            fcitx.reset()
        } finally { fcitx.stop() }
    }
    @Test fun invalidModelCannotOverwriteVerifiedDownload() {
        val previous = QwenModelStore.readyFile(app)?.lastModified()
        val invalid = File(app.cacheDir, "qwen-invalid-test.gguf").apply { writeText("not a model") }
        try {
            assertTrue(runCatching { QwenModelStore.installVerified(app, invalid) }.isFailure)
            if (previous != null) assertEquals(previous, QwenModelStore.readyFile(app)!!.lastModified())
        } finally { invalid.delete() }
    }
}
