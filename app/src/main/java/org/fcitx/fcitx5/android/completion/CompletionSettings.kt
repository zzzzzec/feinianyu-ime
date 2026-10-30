// SPDX-License-Identifier: GPL-3.0-or-later
package org.fcitx.fcitx5.android.completion

import android.content.Context

object CompletionSettings {
    private fun unlocked(context: Context) = android.os.Build.VERSION.SDK_INT < 24 ||
        (context.getSystemService(Context.USER_SERVICE) as android.os.UserManager).isUserUnlocked
    private fun prefs(context: Context) = context.getSharedPreferences("qwen_completion", Context.MODE_PRIVATE)
    fun enabled(context: Context) = unlocked(context) && prefs(context).getBoolean("enabled", true)
    fun setEnabled(context: Context, value: Boolean) { prefs(context).edit().putBoolean("enabled", value).apply() }
    fun contextChars(context: Context) = prefs(context).getInt("context_chars", 128).coerceIn(32, 256)
    fun setContextChars(context: Context, value: Int) { prefs(context).edit().putInt("context_chars", value.coerceIn(32, 256)).apply() }
    fun budgetMs(context: Context) = prefs(context).getInt("budget_ms", 800).coerceIn(300, 10000)
    fun setBudgetMs(context: Context, value: Int) { prefs(context).edit().putInt("budget_ms", value.coerceIn(300, 10000)).apply() }
    fun policy(context: Context) = CompletionConfidence.policy(prefs(context).getString("confidence_policy", null))
    fun setPolicy(context: Context, policy: CompletionPolicy) {
        require(policy in CompletionConfidence.policies)
        prefs(context).edit().putString("confidence_policy", policy.name).apply()
    }
    fun register(context: Context, listener: android.content.SharedPreferences.OnSharedPreferenceChangeListener) {
        if (unlocked(context)) prefs(context).registerOnSharedPreferenceChangeListener(listener)
    }
    fun unregister(context: Context, listener: android.content.SharedPreferences.OnSharedPreferenceChangeListener) {
        if (unlocked(context)) prefs(context).unregisterOnSharedPreferenceChangeListener(listener)
    }
    const val DEBOUNCE_MS = 180L
}
