/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2023 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main.settings.behavior

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.preference.Preference
import androidx.preference.PreferenceScreen
import androidx.preference.SwitchPreference
import kotlinx.coroutines.launch
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.clipboard.ScreenshotMonitor
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.prefs.ManagedPreferenceFragment
import org.fcitx.fcitx5.android.utils.addPreference
import org.fcitx.fcitx5.android.utils.toast

class ClipboardSettingsFragment : ManagedPreferenceFragment(AppPrefs.getInstance().clipboard) {
    private val setting get() = AppPrefs.getInstance().clipboard.clipboardScreenshots
    private var pendingEnable = false
    private var busy = false
    private var permissionRow: Preference? = null
    private val permissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (pendingEnable && ScreenshotMonitor.hasAccess()) enable()
        else if (pendingEnable) requireContext().toast(R.string.screenshot_permission_required)
        pendingEnable = false
        updateSummary()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pendingEnable = savedInstanceState?.getBoolean("enable_screenshots") ?: false
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("enable_screenshots", pendingEnable)
        super.onSaveInstanceState(outState)
    }

    override fun onPreferenceUiCreated(screen: PreferenceScreen) {
        screen.findPreference<SwitchPreference>(setting.key)?.setOnPreferenceChangeListener { _, value ->
            if (value == false) {
                setting.setValue(false)
                findPreference<SwitchPreference>(setting.key)?.isChecked = false
                updateSummary()
            } else if (ScreenshotMonitor.hasAccess()) enable()
            else { pendingEnable = true; permissions.launch(ScreenshotMonitor.permissions()) }
            false
        }
        screen.addPreference(R.string.screenshot_photo_permission) {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${requireContext().packageName}")))
        }
        permissionRow = screen.getPreference(screen.preferenceCount - 1)
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) { ScreenshotMonitor.status.collect { updateSummary() } }
        }
        updateSummary()
    }

    private fun enable() {
        if (busy) return
        busy = true; updateSummary()
        lifecycleScope.launch {
            runCatching { ScreenshotMonitor.prepareEnable() }
                .onSuccess {
                    setting.setValue(true)
                    findPreference<SwitchPreference>(setting.key)?.isChecked = true
                    ScreenshotMonitor.refresh()
                }.onFailure { requireContext().toast(R.string.screenshot_status_error) }
            busy = false; updateSummary()
        }
    }

    private fun updateSummary() {
        if (!isAdded) return
        findPreference<SwitchPreference>(setting.key)?.apply {
            isEnabled = !busy && AppPrefs.getInstance().clipboard.clipboardListening.getValue()
            summary = getString(if (busy) R.string.screenshot_status_preparing
                else if (setting.getValue()) ScreenshotMonitor.status.value else R.string.clipboard_screenshots_summary)
        }
        permissionRow?.setSummary(when {
            ScreenshotMonitor.hasAccess() -> R.string.screenshot_permission_full
            ScreenshotMonitor.hasPartialAccess() -> R.string.screenshot_permission_partial
            else -> R.string.screenshot_permission_none
        })
    }

    override fun onResume() { super.onResume(); ScreenshotMonitor.refresh(); updateSummary() }
}
