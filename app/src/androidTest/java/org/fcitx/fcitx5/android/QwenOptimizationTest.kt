// SPDX-License-Identifier: GPL-3.0-or-later
package org.fcitx.fcitx5.android

import androidx.test.platform.app.InstrumentationRegistry
import android.app.Activity
import android.content.Intent
import kotlinx.coroutines.*
import org.fcitx.fcitx5.android.completion.*
import org.fcitx.fcitx5.android.dataset.InputDataset
import org.fcitx.fcitx5.android.dataset.TelemetrySettings
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.io.File

class QwenOptimizationTest {
    private val app get() = InstrumentationRegistry.getInstrumentation().targetContext
    private var capture = false
    private var telemetry = false
    private var foreground: Activity? = null
    private val prefix = "明天下午我有空，我们可以"
    private fun parse(bytes: ByteArray) = JSONObject(bytes.toString(Charsets.UTF_8))
    private fun generate(text: String, id: Long = QwenCompletionEngine.nextId(), budget: Int = 30000) =
        parse(QwenNative.generate(text.toByteArray(Charsets.UTF_8), id, 8, budget, 0.0, 0.0, 0.0, 12, 1, 0.0))

    @Before fun load() {
        capture = InputDataset.enabled(app); telemetry = TelemetrySettings.performance(app)
        InputDataset.setEnabled(app, false); TelemetrySettings.setPerformance(app, false)
        foreground = InstrumentationRegistry.getInstrumentation().startActivitySync(
            Intent(app, QwenBenchmarkActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        if (QwenModelStore.readyFile(app) == null) QwenModelStore.installVerified(app,
            File(app.getExternalFilesDir("completion-downloads"), "qa-model.gguf"))
        QwenNative.configureThreads(2, 2)
        assertEquals("ready", parse(QwenNative.load(QwenModelStore.readyFile(app)!!.absolutePath)).getString("state"))
        QwenNative.reset()
    }
    @After fun restore() {
        QwenNative.configureThreads(CompletionSettings.decodeThreads(app), CompletionSettings.prefillThreads(app))
        QwenNative.reset()
        InputDataset.setEnabled(app, capture); TelemetrySettings.setPerformance(app, telemetry)
        foreground?.let { activity -> InstrumentationRegistry.getInstrumentation().runOnMainSync { activity.finish() } }
    }

    @Test fun cancelledAndTimedOutWorkPreservesOnlyCompleteSnapshots(): Unit = runBlocking {
        val seed = generate(prefix)
        assertEquals(seed.toString(), "generated", seed.getString("state"))
        val id = QwenCompletionEngine.nextId()
        val pending = async(Dispatchers.Default) { generate(prefix, id) }
        withTimeout(30000) {
            while (true) {
                val state = parse(QwenNative.executionState())
                if (state.optLong("request_id") == id && state.optInt("phase") == 2) break
                check(!pending.isCompleted) { "Generation finished before cancellation could be observed" }
                delay(1)
            }
        }
        QwenNative.cancel(id)
        val cancelled = pending.await()
        assertEquals(cancelled.toString(), "cancelled", cancelled.getString("state"))
        assertTrue(cancelled.getBoolean("cache_preserved"))
        assertEquals("decode", cancelled.getString("cancel_phase"))
        val retry = generate(prefix)
        assertEquals(seed.getInt("prompt_tokens"), retry.getInt("reused_tokens"))
        assertEquals(seed.getJSONArray("candidates").toString(), retry.getJSONArray("candidates").toString())

        val timeout = generate("不同的长前文正在测试超时之后的状态恢复。".repeat(14), budget = 1)
        assertEquals(timeout.toString(), "timeout", timeout.getString("state"))
        val afterTimeout = generate(prefix)
        assertEquals(seed.getInt("prompt_tokens"), afterTimeout.getInt("reused_tokens"))
        assertEquals(seed.getJSONArray("candidates").toString(), afterTimeout.getJSONArray("candidates").toString())

        QwenNative.reset()
        assertEquals(0, parse(QwenNative.runtimeInfo()).getInt("cached_prompt_tokens"))
        val cold = generate(prefix)
        assertEquals(0, cold.getInt("reused_tokens"))
        assertEquals(seed.getJSONArray("candidates").toString(), cold.getJSONArray("candidates").toString())
        val unrelated = generate("这份文件我看过了，有几个地方需要")
        assertEquals(0, unrelated.getInt("reused_tokens"))
    }

    @Test fun cpuBackendAndPersistentPoolsFollowThreadConfiguration() {
        for ((decode, prefill) in listOf(2 to 2, 2 to 3, 2 to 4, 3 to 3, 4 to 4, 4 to 2, 2 to 2)) {
            QwenNative.configureThreads(decode, prefill)
            val info = parse(QwenNative.runtimeInfo())
            assertTrue(info.toString(), info.getString("cpu_backend_library").startsWith("libggml-cpu"))
            assertTrue(info.getBoolean("cpu_repack")); assertTrue(info.getBoolean("persistent_threadpool"))
            assertEquals(decode, info.getInt("threads")); assertEquals(prefill, info.getInt("batch_threads"))
            QwenNative.reset()
            val result = generate(prefix)
            assertEquals(result.toString(), "generated", result.getString("state"))
            assertEquals(decode, result.getInt("threads")); assertEquals(prefill, result.getInt("batch_threads"))
        }
    }

    /** Explicitly opt in on a real phone; this is not part of routine emulator correctness tests. */
    @Test fun benchmarkThreadProfiles() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("qwen_benchmark") == "true")
        val metrics = JSONArray()
        val prompts = listOf(prefix, "今天辛苦了，早点", "这份文件我看过了，有几个地方需要",
            "我不是不同意这个方案，只是担心现在上线会影响老用户，所以我建议")
        val profiles = listOf(2 to 2, 2 to 3, 2 to 4, 3 to 3, 4 to 4)
        val oldDecode = CompletionSettings.decodeThreads(app)
        val oldPrefill = CompletionSettings.prefillThreads(app)
        // Re-create pools on the production worker, including its scheduling priority.
        runBlocking { QwenCompletionEngine.pauseForSpeech(app) }
        QwenNative.unload()
        QwenCompletionEngine.setActive(app, true)
        QwenCompletionEngine.resumeAfterSpeech(app)
        try {
            runBlocking { withTimeout(120000) {
                while (QwenCompletionEngine.state.value !in listOf("ready", "error")) delay(50)
            } }
            assertEquals("ready", QwenCompletionEngine.state.value)
            for (repeat in 0..2) for (order in profiles.indices) {
                val (decode, prefill) = profiles[(order + repeat) % profiles.size]
                CompletionSettings.setDecodeThreads(app, decode)
                CompletionSettings.setPrefillThreads(app, prefill)
                for ((index, text) in prompts.withIndex()) {
                    QwenNative.reset()
                    for (mode in listOf("cold", "warm")) {
                        val result = runBlocking { QwenCompletionEngine.generate(app, text, QwenCompletionEngine.nextId(),
                            30000, CompletionPolicy("benchmark_permissive", 0.0, 0.0, 0.0, 12, 1, 0.0)) }
                        assertTrue(result.toString(), result.getString("state") in listOf("generated", "abstained"))
                        result.remove("text"); result.remove("candidates"); result.remove("candidate_scores")
                        if (android.os.Build.VERSION.SDK_INT >= 29) result.put("thermal_status",
                            (app.getSystemService(android.content.Context.POWER_SERVICE) as android.os.PowerManager).currentThermalStatus)
                        runCatching { File("/proc/self/status").useLines { lines ->
                            lines.firstOrNull { it.startsWith("VmRSS:") }?.let { line -> result.put("process_rss_kb", line.filter(Char::isDigit).toLong()) }
                        } }
                        metrics.put(result.put("case", index).put("repeat", repeat).put("profile_order", order).put("cache_mode", mode))
                    }
                }
            }
            val environment = runBlocking { JSONObject(CompletionPerf.export(app)) }
            environment.remove("records"); environment.remove("threads"); environment.remove("batch_threads")
            File(app.filesDir, "qwen-thread-benchmark.json").writeText(environment
                .put("benchmark_policy", "permissive_fixed_workload").put("metrics", metrics).toString(2))
        } finally {
            CompletionSettings.setDecodeThreads(app, oldDecode)
            CompletionSettings.setPrefillThreads(app, oldPrefill)
            QwenCompletionEngine.setActive(app, false)
        }
    }
}
