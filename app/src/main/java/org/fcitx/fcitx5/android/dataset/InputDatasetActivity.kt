// SPDX-License-Identifier: GPL-3.0-or-later
package org.fcitx.fcitx5.android.dataset

import android.os.Bundle
import android.text.InputType
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts.CreateDocument
import androidx.activity.result.contract.ActivityResultContracts.OpenDocument
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.SwitchCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.*
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.completion.CompletionPerf
import org.fcitx.fcitx5.android.ui.common.SettingsPageActivity
import java.text.DateFormat
import java.util.Date

class InputDatasetActivity : SettingsPageActivity() {
    private lateinit var status: TextView
    private lateinit var cloudStatus: TextView
    private lateinit var address: TextView
    private lateinit var toggle: SwitchCompat
    private lateinit var cloudToggle: SwitchCompat
    private lateinit var performanceToggle: SwitchCompat
    private var updating = false
    private var refreshJob: Job? = null
    private val export = registerForActivityResult(CreateDocument("application/zip")) { uri ->
        if (uri != null) lifecycleScope.launch {
            status.setText(R.string.dataset_exporting)
            runCatching { withContext(Dispatchers.IO) { checkNotNull(contentResolver.openOutputStream(uri, "wt")).use { InputDataset.export(it) } } }
                .onSuccess { message(R.string.dataset_exported) }.onFailure { message(R.string.telemetry_storage_error) }
            refresh()
        }
    }
    private val exportPerf = registerForActivityResult(CreateDocument("application/json")) { uri ->
        if (uri != null) lifecycleScope.launch {
            runCatching { val text = CompletionPerf.export(this@InputDatasetActivity); withContext(Dispatchers.IO) { checkNotNull(contentResolver.openOutputStream(uri, "wt")).use { it.write(text.toByteArray()) } } }
                .onSuccess { message(R.string.dataset_exported) }.onFailure { message(R.string.telemetry_storage_error) }
        }
    }
    private val pairing = registerForActivityResult(OpenDocument()) { uri ->
        if (uri != null) lifecycleScope.launch {
            val parsed = runCatching { withContext(Dispatchers.IO) {
                val bytes = checkNotNull(contentResolver.openInputStream(uri)).use { stream ->
                    val out = java.io.ByteArrayOutputStream(); val buffer = ByteArray(4096)
                    while (true) { val count = stream.read(buffer); if (count < 0) break; require(out.size() + count <= 32768); out.write(buffer, 0, count) }; out.toByteArray()
                }
                bytes.toString(Charsets.UTF_8)
            } }.getOrElse { message(R.string.telemetry_invalid_pairing); return@launch }
            val host = runCatching { TelemetrySettings.normalizeEndpoint(org.json.JSONObject(parsed).getString("server_url")) }.getOrElse { message(R.string.telemetry_invalid_pairing); return@launch }
            AlertDialog.Builder(this@InputDatasetActivity).setTitle(R.string.telemetry_import_pairing).setMessage(host)
                .setNegativeButton(android.R.string.cancel, null).setPositiveButton(R.string.telemetry_pair) { _, _ -> lifecycleScope.launch {
                    runCatching { withContext(Dispatchers.IO) { TelemetrySettings.importPairing(this@InputDatasetActivity, parsed) } }
                        .onSuccess { message(R.string.telemetry_paired) }.onFailure { message(R.string.telemetry_invalid_pairing) }
                    refresh()
                } }.show()
        }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState); InputDataset.initialize(this)
        val page = settingsContent(R.string.dataset_title)
        with(page) {
            section(R.string.telemetry_local)
            toggle = switchRow(R.string.dataset_record, InputDataset.enabled(this@InputDatasetActivity)) {
                if (!updating) { InputDataset.setEnabled(this@InputDatasetActivity, it); lifecycleScope.launch { refresh() } }
            }
            label(getString(R.string.dataset_scope), secondary = true)
            performanceToggle = switchRow(R.string.telemetry_performance, TelemetrySettings.performance(this@InputDatasetActivity)) { if (!updating) TelemetrySettings.setPerformance(this@InputDatasetActivity, it) }
            status = label("")
            actionRow(R.string.dataset_excluded, R.drawable.ic_baseline_list_alt_24) { excludedApps() }
            section(R.string.telemetry_cloud)
            actionRow(R.string.telemetry_address, R.drawable.ic_baseline_edit_24) { editAddress() }
            address = label(TelemetrySettings.endpoint(this@InputDatasetActivity), secondary = true)
            actionRow(R.string.telemetry_import_pairing, R.drawable.ic_baseline_save_24) { pairing.launch(arrayOf("*/*")) }
            cloudToggle = switchRow(R.string.telemetry_sync_enabled, TelemetrySettings.enabled(this@InputDatasetActivity)) { enabled ->
                if (!updating) {
                    if (enabled && TelemetrySettings.connection(this@InputDatasetActivity) == null) { message(R.string.telemetry_pairing_required); cloudToggle.isChecked = false }
                    else { TelemetrySettings.setEnabled(this@InputDatasetActivity, enabled); if (enabled) TelemetrySync.request(this@InputDatasetActivity) }
                }
            }
            switchRow(R.string.telemetry_wifi_only, TelemetrySettings.wifiOnly(this@InputDatasetActivity)) { TelemetrySettings.setConstraints(this@InputDatasetActivity, it, TelemetrySettings.chargingOnly(this@InputDatasetActivity)) }
            switchRow(R.string.telemetry_charging_only, TelemetrySettings.chargingOnly(this@InputDatasetActivity)) { TelemetrySettings.setConstraints(this@InputDatasetActivity, TelemetrySettings.wifiOnly(this@InputDatasetActivity), it) }
            cloudStatus = label("")
            actionRow(R.string.telemetry_test, R.drawable.ic_baseline_system_update_24) { lifecycleScope.launch {
                cloudStatus.setText(R.string.telemetry_connecting)
                runCatching { TelemetrySync.checkConnection(this@InputDatasetActivity) }
                    .onSuccess { message(R.string.telemetry_connected) }
                    .onFailure { message(errorResource((it as? TelemetryFailure)?.reason ?: "network_error")) }
                refresh()
            } }
            actionRow(R.string.telemetry_sync_now, R.drawable.ic_baseline_system_update_24) {
                if (!TelemetrySettings.enabled(this@InputDatasetActivity)) message(R.string.telemetry_enable_sync)
                else { TelemetrySync.request(this@InputDatasetActivity); message(R.string.telemetry_scheduled) }
            }
            actionRow(R.string.telemetry_disconnect, R.drawable.ic_baseline_delete_24) {
                AlertDialog.Builder(this@InputDatasetActivity).setTitle(R.string.telemetry_disconnect).setMessage(R.string.telemetry_disconnect_message)
                    .setNegativeButton(android.R.string.cancel, null).setPositiveButton(android.R.string.ok) { _, _ -> TelemetrySettings.disconnect(this@InputDatasetActivity); lifecycleScope.launch { refresh() } }.show()
            }
            section(R.string.telemetry_export_section)
            actionRow(R.string.dataset_export, R.drawable.ic_baseline_save_24) { export.launch("feinianyu-input-dataset-${System.currentTimeMillis()}.zip") }
            actionRow(R.string.telemetry_perf_export, R.drawable.ic_baseline_save_24) { exportPerf.launch("feinianyu-completion-perf-${System.currentTimeMillis()}.json") }
            actionRow(R.string.dataset_clear, R.drawable.ic_baseline_delete_24) {
                AlertDialog.Builder(this@InputDatasetActivity).setTitle(R.string.dataset_clear).setMessage(R.string.dataset_clear_message)
                    .setNegativeButton(android.R.string.cancel, null).setPositiveButton(android.R.string.ok) { _, _ -> lifecycleScope.launch {
                        runCatching { InputDataset.clear() }.onFailure { message(R.string.telemetry_storage_error) }; refresh()
                    } }.show()
            }
        }
    }
    override fun onResume() { super.onResume(); refreshJob = lifecycleScope.launch { while (isActive) { refresh(); delay(2000) } } }
    override fun onPause() { refreshJob?.cancel(); super.onPause() }
    private fun message(id: Int) = Toast.makeText(this, id, Toast.LENGTH_LONG).show()
    private suspend fun refresh() {
        val (count, bytes) = runCatching { InputDataset.stats() }.getOrElse { status.setText(R.string.dataset_write_error); return }
        val reason = when (InputDataset.pauseReason()) {
            "storage_limit" -> getString(R.string.dataset_limit); "write_error" -> getString(R.string.dataset_write_error); "queue_full" -> getString(R.string.dataset_queue_full)
            else -> getString(when { InputDataset.enabled(this) -> R.string.dataset_running; TelemetrySettings.performance(this) -> R.string.telemetry_performance_running; else -> R.string.dataset_paused })
        }
        status.text = getString(R.string.dataset_status, count, bytes / 1048576.0, reason)
        val connection = TelemetrySettings.connection(this)
        val pending = connection?.let { runCatching { InputDataset.pending(it.destination) }.getOrNull() }
        val last = TelemetrySettings.lastSuccess(this)
        cloudStatus.text = buildString {
            append(getString(if (connection != null) R.string.telemetry_paired else R.string.telemetry_unpaired))
            pending?.let { append("\n"); append(getString(R.string.telemetry_pending, it.count, it.bytes / 1048576.0)) }
            if (last > 0) { append("\n"); append(getString(R.string.telemetry_last_success, DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(last)))) }
            TelemetrySettings.status(this@InputDatasetActivity).takeIf { it.isNotEmpty() }?.let { append("\n"); append(getString(errorResource(it))) }
        }
        address.text = TelemetrySettings.endpoint(this)
        updating = true; toggle.isChecked = InputDataset.enabled(this); cloudToggle.isChecked = TelemetrySettings.enabled(this); performanceToggle.isChecked = TelemetrySettings.performance(this); updating = false
    }
    private fun editAddress() {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            imeOptions = imeOptions or EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
            setText(TelemetrySettings.endpoint(this@InputDatasetActivity)); setSelectAllOnFocus(true)
            setPadding(dp(24), dp(8), dp(24), dp(8))
        }
        val dialog = AlertDialog.Builder(this).setTitle(R.string.telemetry_address).setMessage(R.string.telemetry_address_message).setView(input)
            .setNegativeButton(android.R.string.cancel, null).setPositiveButton(android.R.string.ok, null).create()
        dialog.setOnShowListener { dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            runCatching { TelemetrySettings.setEndpoint(this, input.text.toString()) }
                .onSuccess { dialog.dismiss(); lifecycleScope.launch { refresh() } }.onFailure { input.error = getString(R.string.telemetry_invalid_address) }
        } }; dialog.show()
    }
    private fun errorResource(reason: String) = when (reason) {
        "pairing_required" -> R.string.telemetry_pairing_required
        "waiting_network" -> R.string.telemetry_waiting
        "access_denied" -> R.string.telemetry_access_denied
        "receipt_mismatch", "protocol_error" -> R.string.telemetry_protocol_error
        "batch_conflict" -> R.string.telemetry_conflict
        "batch_too_large" -> R.string.telemetry_too_large
        "rate_limited" -> R.string.telemetry_rate_limited
        "server_error", "http_error" -> R.string.telemetry_server_error
        "redirect_rejected" -> R.string.telemetry_redirect
        else -> R.string.telemetry_network_error
    }
    private fun excludedApps() {
        val packages = (InputDataset.observedApps() + InputDataset.excluded()).filter { it.isNotBlank() }.sorted()
        if (packages.isEmpty()) { message(R.string.dataset_no_apps); return }
        val labels = packages.map { pkg -> runCatching { packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString() }.getOrDefault(pkg) }.toTypedArray()
        val selected = InputDataset.excluded().toMutableSet()
        AlertDialog.Builder(this).setTitle(R.string.dataset_excluded)
            .setMultiChoiceItems(labels, packages.map { it in selected }.toBooleanArray()) { _, which, checked -> if (checked) selected.add(packages[which]) else selected.remove(packages[which]) }
            .setNegativeButton(android.R.string.cancel, null).setPositiveButton(android.R.string.ok) { _, _ -> InputDataset.setExcluded(selected) }.show()
    }
}
