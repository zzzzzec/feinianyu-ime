// SPDX-License-Identifier: GPL-3.0-or-later
package org.fcitx.fcitx5.android.completion

import android.content.Context
import android.os.SystemClock
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

object QwenCompletionEngine {
    private val worker = Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "QwenCompletion").apply { priority = Thread.MIN_PRIORITY } }.asCoroutineDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val ids = AtomicLong()
    private val mutableState = MutableStateFlow("unloaded")
    val state = mutableState.asStateFlow()
    private var loadJob: Job? = null
    private var idleJob: Job? = null
    @Volatile private var active = false
    fun setActive(context: Context, value: Boolean) {
        active = value
        idleJob?.cancel()
        if (value) { ensureLoaded(context); return }
        idleJob = scope.launch {
            delay(60000)
            withContext(worker) {
                if (!active && mutableState.value == "ready") {
                    mutableState.value = "unloading"
                    QwenNative.unload()
                    mutableState.value = "unloaded"
                }
            }
        }
    }
    fun nextId() = ids.incrementAndGet()
    fun cancel(id: Long) { if (id > 0) QwenNative.cancel(id) }
    @Synchronized fun ensureLoaded(context: Context) {
        val app = context.applicationContext
        if (mutableState.value == "ready" || loadJob?.isActive == true) return
        loadJob = scope.launch {
            try {
                QwenModelStore.observe(app)
                QwenModelStore.refresh(app)
                val model = QwenModelStore.readyFile(app) ?: return@launch
                mutableState.value = "loading"
                val data = withContext(worker) { JSONObject(QwenNative.load(model.absolutePath).toString(Charsets.UTF_8)) }
                mutableState.value = data.getString("state")
                CompletionPerf.record(app, data.put("phase", "load"))
                if (!active) setActive(app, false)
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                mutableState.value = "error"
                CompletionPerf.record(app, JSONObject().put("phase", "load").put("state", "error").put("error_type", error.javaClass.simpleName))
            }
        }
    }
    suspend fun generate(context: Context, prompt: String, id: Long, budgetMs: Int): JSONObject {
        val enqueuedAt = SystemClock.elapsedRealtime()
        return withContext(worker) {
            val queuedAt = SystemClock.elapsedRealtime()
            val remaining = budgetMs - (queuedAt - enqueuedAt).toInt()
            val result = try {
                if (remaining <= 0) JSONObject().put("state", "timeout").put("text", "")
                else JSONObject(QwenNative.generate(prompt.toByteArray(Charsets.UTF_8), id, 8, remaining).toString(Charsets.UTF_8))
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                JSONObject().put("state", "inference_error").put("text", "").put("error_type", error.javaClass.simpleName)
            }
            val perf = JSONObject(result.toString()).apply { remove("text"); remove("candidates") }
                .put("request_id", id).put("phase", "inference").put("context_chars", prompt.codePointCount(0, prompt.length))
                .put("worker_elapsed_ms", SystemClock.elapsedRealtime() - queuedAt)
                .put("worker_queue_ms", queuedAt - enqueuedAt)
                .put("request_budget_ms", budgetMs).put("native_budget_ms", remaining)
            CompletionPerf.record(context.applicationContext, perf)
            result
        }
    }
    fun clearContext() { scope.launch(worker) { QwenNative.reset() } }
}
