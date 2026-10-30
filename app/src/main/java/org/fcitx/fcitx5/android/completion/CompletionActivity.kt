// SPDX-License-Identifier: GPL-3.0-or-later
package org.fcitx.fcitx5.android.completion

import android.os.Bundle
import android.text.InputType
import android.view.View
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts.CreateDocument
import androidx.appcompat.widget.AppCompatEditText
import androidx.appcompat.widget.AppCompatSpinner
import androidx.lifecycle.*
import kotlinx.coroutines.*
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.ui.common.SettingsPageActivity

class CompletionExportState : ViewModel() { var payload: String? = null }

class CompletionActivity : SettingsPageActivity() {
    private lateinit var exportState: CompletionExportState
    private val export = registerForActivityResult(CreateDocument("application/json")) { uri ->
        val payload = exportState.payload; exportState.payload = null
        if (uri != null && payload != null) lifecycleScope.launch {
            runCatching { withContext(Dispatchers.IO) {
                checkNotNull(contentResolver.openOutputStream(uri, "wt")).bufferedWriter().use { it.write(payload) }
            } }.onFailure { Toast.makeText(this@CompletionActivity, it.localizedMessage, Toast.LENGTH_LONG).show() }
        }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        exportState = ViewModelProvider(this)[CompletionExportState::class.java]
        val content = settingsContent(R.string.completion_title, followKeyboard = true)
        with(content) {
            switchRow(R.string.completion_enabled, CompletionSettings.enabled(this@CompletionActivity)) {
                CompletionSettings.setEnabled(this@CompletionActivity, it)
                if (it) QwenCompletionEngine.ensureLoaded(this@CompletionActivity)
            }
            section(R.string.completion_model)
            label("Qwen3.5-0.8B-Base · Q4_K_M")
        }
        val status = with(content) { label("", secondary = true) }
        val progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100 }
        content.addView(progress, LinearLayout.LayoutParams(-1, dp(4)).apply { marginStart = dp(16); marginEnd = dp(16) })
        val download = with(content) { actionRow(R.string.completion_download, R.drawable.ic_baseline_save_24) { QwenModelStore.download(this@CompletionActivity) } }
        with(content) { section(R.string.completion_context) }
        val sizes = listOf(32, 64, 128, 256)
        spinner(content, sizes.map { "$it 字" }, sizes.indexOf(CompletionSettings.contextChars(this)).coerceAtLeast(0)) {
            CompletionSettings.setContextChars(this, sizes[it])
        }
        with(content) { section(R.string.completion_budget) }
        val budgets = listOf(500, 800, 1500, 3000, 10000)
        spinner(content, budgets.map { "$it ms" }, budgets.indexOf(CompletionSettings.budgetMs(this)).coerceAtLeast(0)) {
            CompletionSettings.setBudgetMs(this, budgets[it])
        }
        with(content) { section(R.string.completion_try) }
        content.addView(AppCompatEditText(this).apply {
            id = R.id.completion_test_input
            hint = getString(R.string.completion_try_hint)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 2; maxLines = 4; textSize = 16f
        }, LinearLayout.LayoutParams(-1, -2).apply { marginStart = dp(16); marginEnd = dp(16) })
        with(content) {
            section(R.string.completion_perf)
            actionRow(R.string.completion_export, R.drawable.ic_baseline_save_24) {
                lifecycleScope.launch {
                    runCatching {
                        exportState.payload = CompletionPerf.export(this@CompletionActivity)
                        export.launch("feinianyu-completion-perf-${System.currentTimeMillis()}.json")
                    }.onFailure { Toast.makeText(this@CompletionActivity, it.localizedMessage, Toast.LENGTH_LONG).show() }
                }
            }
            actionRow(R.string.clear, R.drawable.ic_baseline_delete_24) { lifecycleScope.launch { CompletionPerf.clear(this@CompletionActivity) } }
        }
        lifecycleScope.launch { repeatOnLifecycle(Lifecycle.State.STARTED) {
            QwenModelStore.observe(this@CompletionActivity)
            launch { QwenModelStore.state.collect { value ->
                status.text = when (value.phase) {
                    "ready" -> getString(R.string.completion_model_ready)
                    "downloading" -> getString(R.string.completion_model_downloading, value.received / 1048576, QwenModelStore.SIZE / 1048576)
                    "verifying" -> getString(R.string.completion_model_verifying)
                    "error" -> value.error
                    else -> getString(R.string.completion_model_missing)
                }
                progress.visibility = if (value.phase == "downloading") View.VISIBLE else View.GONE
                progress.progress = (100 * value.received / QwenModelStore.SIZE).toInt()
                download.visibility = if (value.phase == "ready") View.GONE else View.VISIBLE
                download.isEnabled = value.phase !in setOf("downloading", "verifying")
                if (value.phase == "ready" && CompletionSettings.enabled(this@CompletionActivity)) QwenCompletionEngine.ensureLoaded(this@CompletionActivity)
            } }
            awaitCancellation()
        } }
    }
    private fun spinner(parent: LinearLayout, labels: List<String>, selected: Int, changed: (Int) -> Unit) {
        parent.addView(AppCompatSpinner(this).apply {
            adapter = ArrayAdapter(this@CompletionActivity, android.R.layout.simple_spinner_dropdown_item, labels)
            setSelection(selected)
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onNothingSelected(parent: AdapterView<*>?) {}
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) { changed(position) }
            }
        }, LinearLayout.LayoutParams(-1, dp(56)).apply { marginStart = dp(16); marginEnd = dp(16) })
    }
}
