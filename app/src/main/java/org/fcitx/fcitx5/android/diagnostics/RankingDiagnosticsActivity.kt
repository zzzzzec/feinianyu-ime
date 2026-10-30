// SPDX-License-Identifier: GPL-3.0-or-later
package org.fcitx.fcitx5.android.diagnostics

import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.view.Menu
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts.CreateDocument
import androidx.appcompat.widget.AppCompatEditText
import androidx.appcompat.widget.AppCompatSpinner
import androidx.lifecycle.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.daemon.FcitxConnection
import org.fcitx.fcitx5.android.daemon.FcitxDaemon
import org.fcitx.fcitx5.android.ui.main.LogActivity
import org.fcitx.fcitx5.android.ui.common.SettingsPageActivity
import org.fcitx.fcitx5.android.utils.item
import splitties.resources.styledColor
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class RankingExportState : ViewModel() { var pending: String? = null }

class RankingDiagnosticsActivity : SettingsPageActivity() {
    private lateinit var connection: FcitxConnection
    private lateinit var status: TextView
    private lateinit var detail: TextView
    private lateinit var history: Spinner
    private lateinit var exportState: RankingExportState
    private var records = emptyList<JSONObject>()
    private val clock = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())
    private val export = registerForActivityResult(CreateDocument("application/json")) { uri ->
        val payload = exportState.pending
        exportState.pending = null
        if (uri != null) lifecycleScope.launch {
            runCatching {
                checkNotNull(payload)
                withContext(Dispatchers.IO) {
                    checkNotNull(contentResolver.openOutputStream(uri, "wt")).bufferedWriter(Charsets.UTF_8).use { it.write(payload) }
                }
            }.onSuccess { message(getString(R.string.ranking_exported)) }
                .onFailure { message(getString(R.string.ranking_error, it.localizedMessage)) }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        exportState = ViewModelProvider(this)[RankingExportState::class.java]
        connection = FcitxDaemon.connect(javaClass.name)
        val content = settingsContent(R.string.ranking_diagnostics, followKeyboard = true)
        with(content) {
            switchRow(R.string.ranking_record, RankingDiagnosticsSettings.enabled(this@RankingDiagnosticsActivity)) { checked ->
                RankingDiagnosticsSettings.setEnabled(this@RankingDiagnosticsActivity, checked)
                lifecycleScope.launch { connection.runOnReady { setSuzuDiagnostics(checked) }; refresh() }
            }
            label(getString(R.string.ranking_retention), secondary = true)
            actionRow(R.string.ranking_export, R.drawable.ic_baseline_save_24) { exportRecords() }
            section(R.string.ranking_test_input)
        }
        content.addView(AppCompatEditText(this).apply {
            hint = getString(R.string.ranking_try_input)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            textSize = 16f
            maxLines = 3
            id = View.generateViewId()
        }, LinearLayout.LayoutParams(-1, -2).apply {
            marginStart = dp(16); marginEnd = dp(16)
        })
        with(content) {
            status = label("", secondary = true)
            section(R.string.ranking_recent_records)
        }
        history = AppCompatSpinner(this)
        content.addView(history, LinearLayout.LayoutParams(-1, -2).apply {
            marginStart = dp(16); marginEnd = dp(16)
        })
        history.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) { showRecord(position) }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
        with(content) {
            detail = label(getString(R.string.ranking_empty)).apply { textSize = 14f; setTextIsSelectable(true) }
            actionRow(R.string.real_time_logs, R.drawable.ic_baseline_developer_mode_24) {
                startActivity(Intent(this@RankingDiagnosticsActivity, LogActivity::class.java))
            }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                connection.runOnReady { setSuzuDiagnostics(RankingDiagnosticsSettings.enabled(this@RankingDiagnosticsActivity)) }
                while (true) { refresh(); delay(1000) }
            }
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        val tint = styledColor(android.R.attr.colorControlNormal)
        menu.item(R.string.ranking_refresh, R.drawable.ic_baseline_sync_24, tint, true) { lifecycleScope.launch { refresh() } }
        menu.item(R.string.clear) { lifecycleScope.launch { connection.runOnReady { clearSuzuDiagnostics() }; refresh() } }
        return true
    }

    private fun exportRecords() {
        lifecycleScope.launch {
            runCatching {
                exportState.pending = RankingDiagnosticsExport.create(connection.runOnReady { suzuDiagnostics() })
                export.launch("feinianyu-ranking-${SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(Date())}.json")
            }.onFailure { message(getString(R.string.ranking_error, it.localizedMessage)) }
        }
    }

    private suspend fun refresh() {
        runCatching {
            val snapshot = JSONObject(connection.runOnReady { suzuDiagnostics() })
            val runtime = snapshot.getJSONObject("runtime")
            status.text = getString(R.string.ranking_status, reason(runtime.getString("state")),
                runtime.getInt("scored"), runtime.getInt("reorders"), runtime.getInt("timeouts"))
            val array = snapshot.getJSONArray("records")
            val updated = (0 until array.length()).reversed().map { array.getJSONObject(it) }
            if (updated.firstOrNull()?.optLong("id") == records.firstOrNull()?.optLong("id") && updated.size == records.size) return
            val followLatest = history.selectedItemPosition <= 0
            val selected = records.getOrNull(history.selectedItemPosition)?.optLong("id")
            records = updated
            history.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, records.map {
                "${clock.format(Date(it.getLong("timestamp_ms")))}  ${it.getString("input")} · ${reason(it.getString("reason"))}"
            }).apply { setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
            val index = if (followLatest) 0 else records.indexOfFirst { it.optLong("id") == selected }.coerceAtLeast(0)
            history.setSelection(index)
            showRecord(index)
        }.onFailure { status.text = getString(R.string.ranking_error, it.localizedMessage) }
    }

    private fun showRecord(index: Int) {
        val record = records.getOrNull(index) ?: run { detail.setText(R.string.ranking_empty); return }
        fun score(row: JSONObject, key: String) = if (row.isNull(key)) "—" else String.format(Locale.ROOT, "%.3f", row.getDouble(key))
        detail.text = buildString {
            appendLine(getString(R.string.ranking_result, reason(record.getString("reason"))))
            appendLine(getString(R.string.ranking_input, record.getString("input")))
            appendLine(getString(R.string.ranking_context, record.getString("context")))
            appendLine(getString(R.string.ranking_timing, score(record, "elapsed_ms"), score(record, "model_ms")))
            if (record.getBoolean("top_protected")) appendLine(getString(R.string.ranking_top_protected))
            val rows = record.getJSONArray("candidates")
            if (rows.length() > 0) {
                val candidates = (0 until rows.length()).map { rows.getJSONObject(it) }
                appendLine(getString(R.string.ranking_order, candidates.joinToString(" · ") { it.getString("text") },
                    candidates.sortedBy { it.getInt("final_rank") }.joinToString(" · ") { it.getString("text") }))
                appendLine()
                candidates.forEach { row ->
                    appendLine("${row.getInt("original_rank")} → ${row.getInt("final_rank")}  ${row.getString("text")}")
                    appendLine(if (row.isNull("raw_score")) reason(row.getString("excluded").ifEmpty { "not_scored" })
                    else getString(R.string.ranking_scores, score(row, "raw_score"), score(row, "rank_penalty"), score(row, "final_score")))
                }
            }
        }
    }

    private fun reason(code: String): String {
        val name = resources.getIdentifier("ranking_reason_$code", "string", packageName)
        return if (name == 0) code else getString(name)
    }
    private fun message(text: String) { Toast.makeText(this, text, Toast.LENGTH_LONG).show() }
    override fun onDestroy() { FcitxDaemon.disconnect(javaClass.name); super.onDestroy() }
}
