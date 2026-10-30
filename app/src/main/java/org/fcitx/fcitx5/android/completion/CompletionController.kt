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
    private val display: (List<String>?, (() -> Boolean)?) -> Boolean,
    private val commit: (String) -> Unit
) {
    private var job: Job? = null
    private var epoch = 0L
    private var activeId = 0L
    private data class Offer(val snapshot: CompletionSnapshot, val texts: List<String>, val policy: String)
    private var offered: Offer? = null
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
            val raw = result.optJSONArray("candidates")
            val policy = result.optString("confidence_policy")
            val texts = CompletionText.suggestions((0 until (raw?.length() ?: 0)).map { raw!!.optString(it) })
            val elapsed = SystemClock.elapsedRealtime() - start
            if (result.optString("state") != "generated" || texts.isEmpty() || elapsed >= totalBudget) {
                CompletionPerf.record(context, JSONObject().put("request_id", id).put("phase", "ui")
                    .put("state", if (elapsed >= totalBudget) "timeout" else if (texts.isEmpty() && result.optString("state") == "generated") "filtered" else result.optString("state"))
                    .put("end_to_end_ms", elapsed).put("budget_ms", totalBudget).put("candidate_count", texts.size)
                    .put("confidence_policy", policy))
                return@launch
            }
            offered = Offer(input, texts, policy)
            val accepted = display(texts) {
                val now = SystemClock.elapsedRealtime() - start
                val valid = CompletionSettings.enabled(context) && epoch == expectedEpoch && snapshot() == input && now < totalBudget
                if (!valid) offered = null
                CompletionPerf.record(context, JSONObject().put("request_id", id).put("phase", "ui")
                    .put("state", if (valid) "shown" else "late_or_stale")
                    .put("end_to_end_ms", now).put("debounce_ms", CompletionSettings.DEBOUNCE_MS)
                    .put("budget_ms", totalBudget).put("candidate_count", texts.size).put("confidence_policy", policy)
                    .put("suggestion_chars", texts.sumOf { it.codePointCount(0, it.length) }))
                valid
            }
            if (!accepted) offered = null
        }
    }
    fun accept(index: Int): Boolean {
        val (input, texts, policy) = offered ?: return false
        val text = texts.getOrNull(index) ?: return false
        if (!CompletionSettings.enabled(context) || snapshot() != input) { invalidate(); return false }
        val id = activeId
        invalidate()
        commit(text)
        CompletionPerf.record(context, JSONObject().put("request_id", id).put("phase", "accept").put("state", "accepted")
            .put("selected_index", index).put("candidate_count", texts.size).put("confidence_policy", policy))
        return true
    }
}
