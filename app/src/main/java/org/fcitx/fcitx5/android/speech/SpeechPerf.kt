// SPDX-License-Identifier: GPL-3.0-or-later
package org.fcitx.fcitx5.android.speech

import android.content.Context
import android.os.Debug
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Bounded local diagnostics; neither audio nor recognized text is stored here. */
object SpeechPerf {
    private val mutex = Mutex()
    private fun file(context: Context) = File(context.noBackupFilesDir, "speech-perf.jsonl")
    suspend fun record(context: Context, phase: String, fields: JSONObject = JSONObject()) = withContext(Dispatchers.IO) {
        mutex.withLock {
            runCatching {
                val target = file(context)
                if (target.length() > 256 * 1024) {
                    val tail = target.readLines().takeLast(200)
                    target.writeText(tail.joinToString("\n", postfix = "\n"))
                }
                if (!fields.has("provider")) fields.put("provider", SpeechSettings.provider(context))
                if (fields.optString("provider") == SpeechProviders.senseVoice.id) {
                    fields.put("model", SpeechModelStore.ID).put("runtime", "sherpa-onnx-2.0.1-static")
                        .put("onnxruntime", "1.28.2").put("threads", 2).put("language", SpeechSettings.language(context))
                }
                val rssKiB = File("/proc/self/status").useLines { lines ->
                    lines.firstOrNull { it.startsWith("VmRSS:") }?.filter(Char::isDigit)?.toLongOrNull()
                }
                if (rssKiB != null) fields.put("process_rss_bytes", rssKiB * 1024)
                target.appendText(fields.put("timestamp_ms", System.currentTimeMillis()).put("phase", phase)
                    .put("app_version", org.fcitx.fcitx5.android.BuildConfig.VERSION_NAME)
                    .put("native_heap_bytes", Debug.getNativeHeapAllocatedSize()).toString() + "\n")
            }
        }
    }
    suspend fun export(context: Context): String = withContext(Dispatchers.IO) { mutex.withLock {
        val entries = JSONArray()
        if (file(context).isFile) file(context).forEachLine { line -> runCatching { entries.put(JSONObject(line)) } }
        JSONObject().put("schema_version", 1).put("records", entries).toString(2)
    } }
    suspend fun clear(context: Context) = withContext(Dispatchers.IO) { mutex.withLock { file(context).delete() } }
}
