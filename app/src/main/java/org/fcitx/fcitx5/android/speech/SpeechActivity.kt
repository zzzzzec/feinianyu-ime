// SPDX-License-Identifier: GPL-3.0-or-later
package org.fcitx.fcitx5.android.speech

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.widget.AppCompatEditText
import androidx.appcompat.widget.AppCompatSpinner
import androidx.lifecycle.*
import kotlinx.coroutines.launch
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.ui.common.SettingsPageActivity

class SpeechExportState : ViewModel() { var payload: String? = null }

class SpeechActivity : SettingsPageActivity() {
    private lateinit var exportState: SpeechExportState
    private lateinit var permissionButton: Button
    private val permission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { updatePermission() }
    private val export = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        val payload = exportState.payload; exportState.payload = null
        if (uri != null && payload != null) lifecycleScope.launch {
            runCatching { kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                checkNotNull(contentResolver.openOutputStream(uri, "wt")).bufferedWriter().use { it.write(payload) }
            } }.onFailure { Toast.makeText(this@SpeechActivity, it.localizedMessage, Toast.LENGTH_LONG).show() }
        }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        exportState = ViewModelProvider(this)[SpeechExportState::class.java]
        val content = settingsContent(R.string.speech_title, followKeyboard = true)
        with(content) {
            switchRow(R.string.speech_show_microphone, SpeechSettings.enabled(this@SpeechActivity)) {
                SpeechSettings.setEnabled(this@SpeechActivity, it)
            }
            section(R.string.speech_provider)
        }
        val providers = SpeechProviders.available
        spinner(content, providers.map { it.name }, providers.indexOfFirst { it.id == SpeechSettings.provider(this) }.coerceAtLeast(0)) {
            SpeechSettings.setProvider(this, providers[it].id)
        }.id = R.id.speech_provider_selector
        with(content) { section(R.string.speech_language) }
        val languages = listOf("zh", "auto", "yue", "en", "ja", "ko")
        spinner(content, listOf("普通话", "自动识别", "粤语", "English", "日本語", "한국어"),
            languages.indexOf(SpeechSettings.language(this)).coerceAtLeast(0)) { SpeechSettings.setLanguage(this, languages[it]) }
            .id = R.id.speech_language_selector
        with(content) {
            section(R.string.speech_model)
            label("SenseVoice Small · int8 · 230 MiB")
        }
        val status = with(content) { label("", secondary = true) }.apply { id = R.id.speech_model_status }
        val progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100 }
        content.addView(progress, LinearLayout.LayoutParams(-1, dp(4)).apply { marginStart = dp(16); marginEnd = dp(16) })
        with(content) {
            actionRow(R.string.speech_download, R.drawable.ic_baseline_save_24) { SpeechModelStore.download(this@SpeechActivity) }
                .apply { id = R.id.speech_download_button }
            switchRow(R.string.speech_wifi_only, SpeechSettings.wifiOnly(this@SpeechActivity)) {
                SpeechSettings.setWifiOnly(this@SpeechActivity, it)
                SpeechModelStore.networkPolicyChanged(this@SpeechActivity)
            }
        }
        with(content) {
            section(R.string.speech_microphone)
            permissionButton = actionRow(R.string.speech_allow_microphone, R.drawable.ic_baseline_keyboard_voice_24) {
                if (SpeechSettings.hasPermission(this@SpeechActivity) || permissionBlocked()) {
                    startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
                } else {
                    getPreferences(MODE_PRIVATE).edit().putBoolean("permission_requested", true).apply()
                    permission.launch(Manifest.permission.RECORD_AUDIO)
                }
            }.apply { id = R.id.speech_permission_button }
            section(R.string.speech_try)
            addView(AppCompatEditText(this@SpeechActivity).apply {
                id = R.id.speech_test_input
                hint = getString(R.string.speech_try_hint)
                inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
                minLines = 2; textSize = 16f
            }, LinearLayout.LayoutParams(-1, -2).apply { marginStart = dp(16); marginEnd = dp(16) })
            section(R.string.speech_perf)
            actionRow(R.string.speech_export_perf, R.drawable.ic_baseline_save_24) {
                lifecycleScope.launch {
                    exportState.payload = SpeechPerf.export(this@SpeechActivity)
                    export.launch("feinianyu-speech-perf-${System.currentTimeMillis()}.json")
                }
            }
            actionRow(R.string.clear, R.drawable.ic_baseline_delete_24) { lifecycleScope.launch { SpeechPerf.clear(this@SpeechActivity) } }
            actionRow(R.string.speech_model_license, R.drawable.ic_baseline_info_24) {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/modelscope/FunASR/blob/main/MODEL_LICENSE")))
            }
        }
        lifecycleScope.launch { repeatOnLifecycle(Lifecycle.State.STARTED) {
            SpeechModelStore.observe(this@SpeechActivity)
            SpeechModelStore.state.collect { value ->
                status.text = when (value.phase) {
                    "ready" -> getString(R.string.speech_model_ready)
                    "downloading" -> getString(R.string.speech_model_downloading, value.received / 1048576, SpeechModelStore.size / 1048576)
                    "verifying" -> getString(R.string.speech_model_verifying)
                    "error" -> value.error
                    else -> getString(R.string.speech_model_missing)
                }
                progress.visibility = if (value.phase == "downloading") View.VISIBLE else View.GONE
                progress.progress = (100L * value.received / SpeechModelStore.size).toInt()
                // The wifi switch follows the action in the same section; retrieve the action by ID.
                findViewById<Button>(R.id.speech_download_button).apply {
                    visibility = if (value.phase == "ready") View.GONE else View.VISIBLE
                    isEnabled = value.phase !in setOf("downloading", "verifying")
                }
            }
        } }
    }
    private fun spinner(parent: LinearLayout, labels: List<String>, selected: Int, changed: (Int) -> Unit): AppCompatSpinner {
        val spinner = AppCompatSpinner(this).apply {
            adapter = ArrayAdapter(this@SpeechActivity, android.R.layout.simple_spinner_dropdown_item, labels)
            setSelection(selected)
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onNothingSelected(parent: AdapterView<*>?) {}
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) { changed(position) }
            }
        }
        parent.addView(spinner, LinearLayout.LayoutParams(-1, dp(56)).apply { marginStart = dp(16); marginEnd = dp(16) })
        return spinner
    }
    override fun onResume() { super.onResume(); if (::permissionButton.isInitialized) updatePermission() }
    private fun permissionBlocked() = getPreferences(MODE_PRIVATE).getBoolean("permission_requested", false) &&
        !shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO)
    private fun updatePermission() { permissionButton.setText(when {
        SpeechSettings.hasPermission(this) -> R.string.speech_microphone_allowed
        permissionBlocked() -> R.string.speech_microphone_settings
        else -> R.string.speech_allow_microphone
    }) }
}
