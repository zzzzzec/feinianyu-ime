// SPDX-License-Identifier: GPL-3.0-or-later
package org.fcitx.fcitx5.android.update

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.*
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.fcitx.fcitx5.android.BuildConfig
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.ui.common.SettingsPageActivity

class UpdateActivity : SettingsPageActivity() {
    private lateinit var status: TextView
    private lateinit var notes: TextView
    private lateinit var install: Button
    private var resumeInstall = false
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val content = settingsContent(R.string.app_updates)
        with(content) {
            label(getString(R.string.update_current_version, BuildConfig.VERSION_NAME))
            switchRow(R.string.update_automatic, AppUpdater.automatic(this@UpdateActivity)) {
                AppUpdater.setAutomatic(this@UpdateActivity, it)
            }
            actionRow(R.string.update_check, R.drawable.ic_baseline_sync_24) {
                lifecycleScope.launch { AppUpdater.check(this@UpdateActivity, manual = true) }
            }
            install = actionRow(R.string.update_install, R.drawable.ic_baseline_system_update_24) { installUpdate() }.apply { isEnabled = false }
            status = label("", secondary = true)
            section(R.string.update_changelog)
            notes = label("", secondary = true).apply { setTextIsSelectable(true) }
        }
        lifecycleScope.launch {
            AppUpdater.state.collect { state ->
                status.text = state.message
                notes.text = state.notes.ifEmpty { AppUpdater.latestNotes(this@UpdateActivity) }
                install.isEnabled = state.ready
            }
        }
        lifecycleScope.launch {
            if (AppUpdater.pendingFile(this@UpdateActivity) != null) {
                notes.text = AppUpdater.pendingNotes(this@UpdateActivity)
                status.text = getString(R.string.update_ready, AppUpdater.pendingName(this@UpdateActivity))
                install.isEnabled = true
            } else AppUpdater.check(this@UpdateActivity, manual = false)
        }
    }
    private fun installUpdate() {
        if (Build.VERSION.SDK_INT >= 26 && !packageManager.canRequestPackageInstalls()) {
            resumeInstall = true
            startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")))
            return
        }
        lifecycleScope.launch {
            install.isEnabled = false
            runCatching { withContext(Dispatchers.IO) { AppUpdater.verifyPending(this@UpdateActivity) } }
                .onSuccess { file ->
                    val uri = FileProvider.getUriForFile(this@UpdateActivity, "$packageName.updates", file)
                    startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
                }
                .onFailure {
                    AppUpdater.discardPending(this@UpdateActivity)
                    status.text = getString(R.string.update_failed, it.message)
                }
            install.isEnabled = AppUpdater.pendingFile(this@UpdateActivity) != null
        }
    }
    override fun onResume() {
        super.onResume()
        if (resumeInstall && Build.VERSION.SDK_INT >= 26 && packageManager.canRequestPackageInstalls()) {
            resumeInstall = false; installUpdate()
        }
    }
}
