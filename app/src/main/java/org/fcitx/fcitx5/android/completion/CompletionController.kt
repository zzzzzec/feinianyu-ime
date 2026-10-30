// SPDX-License-Identifier: GPL-3.0-or-later
package org.fcitx.fcitx5.android.completion

import android.content.Context
import android.os.SystemClock
import kotlinx.coroutines.*
import org.json.JSONObject

data class CompletionSnapshot(val before: String, val cursor: Int, val editor: Long)

/** Main-thread state machine. Inference never blocks a key; stale results cannot be displayed or accepted. */
class CompletionController(
    private val context: Context,
    private val scope: CoroutineScope,
    private val snapshot: () -> CompletionSnapshot?,
    private val display: (String?, (() -> Boolean)?) -> Boolean,
    private val commit: (String) -> Unit
) {
    private var job: Job? = null
    private var epoch = 0L
    private var activeId = 0L
    private var offered: Pair<CompletionSnapshot, String>? = null
    fun invalidate(clearContext: Boolean = false) {
        epoch++
        job?.cancel(); job = null
        QwenCompletionEngine.cancel(activeId); activeId = 0
        offered = null; display(null, null)
        if (clearContext) QwenCompletionEngine.clearContext()
    }
    fun changed() {
        invalidate()
        if (!CompletionSettings.enabled(context)) return
        val start = SystemClock.elapsedRealtime()
        val expectedEpoch = epoch
        QwenCompletionEngine.ensureLoaded(context)
        job = scope.launch {
            delay(CompletionSettings.DEBOUNCE_MS)
            val input = snapshot() ?: return@launch
            if (QwenCompletionEngine.state.value != "ready") return@launch
            val id = QwenCompletionEngine.nextId(); activeId = id
            val totalBudget = CompletionSettings.budgetMs(context)
            val remaining = totalBudget - (SystemClock.elapsedRealtime() - start).toInt()
            if (remaining <= 0) return@launch
            val result = QwenCompletionEngine.generate(context, input.before, id, remaining)
            if (!CompletionSettings.enabled(context) || epoch != expectedEpoch || snapshot() != input) return@launch
            val text = CompletionText.suggestion(result.optString("text"))
            val elapsed = SystemClock.elapsedRealtime() - start
            if (result.optString("state") != "generated" || text.isEmpty() || elapsed >= totalBudget) {
                CompletionPerf.record(context, JSONObject().put("request_id", id).put("phase", "ui")
                    .put("state", if (elapsed >= totalBudget) "timeout" else result.optString("state"))
                    .put("end_to_end_ms", elapsed).put("budget_ms", totalBudget))
                return@launch
            }
            offered = input to text
            val accepted = display(text) {
                val now = SystemClock.elapsedRealtime() - start
                val valid = CompletionSettings.enabled(context) && epoch == expectedEpoch && snapshot() == input && now < totalBudget
                if (!valid) offered = null
                CompletionPerf.record(context, JSONObject().put("request_id", id).put("phase", "ui")
                    .put("state", if (valid) "shown" else "late_or_stale")
                    .put("end_to_end_ms", now).put("debounce_ms", CompletionSettings.DEBOUNCE_MS)
                    .put("budget_ms", totalBudget).put("suggestion_chars", text.codePointCount(0, text.length)))
                valid
            }
            if (!accepted) offered = null
        }
    }
    fun accept(): Boolean {
        val (input, text) = offered ?: return false
        if (!CompletionSettings.enabled(context) || snapshot() != input) { invalidate(); return false }
        val id = activeId
        invalidate()
        commit(text)
        CompletionPerf.record(context, JSONObject().put("request_id", id).put("phase", "accept").put("state", "accepted"))
        return true
    }
}
