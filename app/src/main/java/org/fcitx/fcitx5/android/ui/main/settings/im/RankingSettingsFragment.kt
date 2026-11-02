/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.fcitx.fcitx5.android.ui.main.settings.im

import android.content.Intent
import androidx.preference.PreferenceScreen
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.prefs.ManagedPreferenceFragment
import org.fcitx.fcitx5.android.diagnostics.RankingDiagnosticsActivity
import org.fcitx.fcitx5.android.utils.addPreference

class RankingSettingsFragment : ManagedPreferenceFragment(AppPrefs.getInstance().ranking) {
    override fun onPreferenceUiCreated(screen: PreferenceScreen) {
        screen.addPreference(R.string.ranking_diagnostics, icon = R.drawable.ic_baseline_sort_24) {
            startActivity(Intent(requireContext(), RankingDiagnosticsActivity::class.java))
        }
    }
}
