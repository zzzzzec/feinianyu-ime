// SPDX-License-Identifier: GPL-3.0-or-later
package org.fcitx.fcitx5.android.completion

import android.content.Context
import android.os.Build
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.fcitx.fcitx5.android.BuildConfig
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.concurrent.Executors

/** Bounded, persistent timing records. Prompts, generated text and editor/package names are never written. */
object CompletionPerf {
    private val io = Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "CompletionPerf").apply { priority = Thread.MIN_PRIORITY } }.asCoroutineDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + io)
    private val mutex = Mutex()
    private val sessionId = UUID.randomUUID().toString()
    private fun file(context: Context) = File(context.noBackupFilesDir, "completion-perf.jsonl")
    fun record(context: Context, data: JSONObject) {
        val line = JSONObject(data.toString()).put("wall_time_ms", System.currentTimeMillis())
            .put("session_id", sessionId).put("app_version", BuildConfig.VERSION_NAME)
            .put("build_commit", BuildConfig.BUILD_GIT_HASH)
            .put("model_id", if (data.optString("source") == "dictionary" || data.optString("phase") == "dictionary") JSONObject.NULL else QwenModelStore.ID).toString()
        scope.launch { runCatching { mutex.withLock {
            val entry = JSONObject(line)
            if (entry.optString("phase") in setOf("load", "inference")) {
                runCatching {
                    File("/proc/self/status").useLines { rows -> rows.forEach { row ->
                        when {
                            row.startsWith("VmRSS:") -> entry.put("process_rss_kb", row.filter { it.isDigit() }.toLong())
                            row.startsWith("VmHWM:") -> entry.put("process_peak_rss_kb", row.filter { it.isDigit() }.toLong())
                        }
                    } }
                    if (Build.VERSION.SDK_INT >= 29) entry.put("thermal_status",
                        (context.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager).currentThermalStatus)
                }
            }
            val output = file(context)
            if (output.length() > 512 * 1024) {
                val retained = output.useLines { it.toList().takeLast(250) }
                output.writeText(retained.joinToString("\n", postfix = "\n"))
            }
            output.appendText(entry.toString() + "\n")
        } } } // Timing telemetry must never crash the keyboard (e.g. full storage).
    }
    suspend fun export(context: Context): String = withContext(io) { mutex.withLock {
        val records = JSONArray()
        file(context).takeIf { it.isFile }?.useLines { lines -> lines.forEach { line ->
            runCatching { records.put(JSONObject(line)) }
        } }
        JSONObject().put("schema", 1).put("app_version", BuildConfig.VERSION_NAME)
            .put("build_commit", BuildConfig.BUILD_GIT_HASH)
            .put("runtime", "llama.cpp-b11632").put("threads", 2)
            .put("candidate_limit", CompletionText.MAX_SUGGESTIONS).put("generation", "confidence-gated-batched-greedy-v2")
            .put("routing", "fixed-dictionary-first-v1").put("dictionary_enabled", CompletionSettings.dictionaryEnabled(context))
            .put("confidence_policy", CompletionSettings.policy(context).name)
            .put("model_id", QwenModelStore.ID).put("model_sha256", QwenModelStore.SHA256)
            .put("device", JSONObject().put("manufacturer", Build.MANUFACTURER).put("model", Build.MODEL)
                .put("sdk", Build.VERSION.SDK_INT).put("abi", Build.SUPPORTED_ABIS.firstOrNull())
                .put("emulator", Build.FINGERPRINT.contains("generic") || Build.PRODUCT.contains("sdk_gphone")))
            .put("records", records).toString(2)
    } }
    suspend fun clear(context: Context) = withContext(io) { mutex.withLock { file(context).delete(); Unit } }
}
