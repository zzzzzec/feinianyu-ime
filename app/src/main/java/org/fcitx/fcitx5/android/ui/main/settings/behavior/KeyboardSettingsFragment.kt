/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2023 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main.settings.behavior

import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.prefs.ManagedPreferenceFragment
import androidx.preference.Preference
import androidx.preference.PreferenceScreen

class KeyboardSettingsFragment : ManagedPreferenceFragment(AppPrefs.getInstance().keyboard) {
    override fun onPreferenceUiCreated(screen: PreferenceScreen) {
        // Voice input now has its own settings page. Keep the old keys readable for older backups.
        listOf("show_voice_input_button", "preferred_voice_input").forEach { key ->
            screen.findPreference<Preference>(key)?.let { screen.removePreference(it) }
        }
    }
}
