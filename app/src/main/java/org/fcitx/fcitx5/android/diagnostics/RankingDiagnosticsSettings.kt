// SPDX-License-Identifier: GPL-3.0-or-later
package org.fcitx.fcitx5.android.diagnostics

import android.content.Context

object RankingDiagnosticsSettings {
    private fun prefs(context: Context) = context.getSharedPreferences("ranking_diagnostics", Context.MODE_PRIVATE)
    fun enabled(context: Context) = prefs(context).getBoolean("enabled", false)
    fun setEnabled(context: Context, enabled: Boolean) { prefs(context).edit().putBoolean("enabled", enabled).apply() }
}
