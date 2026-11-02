// SPDX-License-Identifier: GPL-3.0-or-later
package org.fcitx.fcitx5.android.speech

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat

object SpeechSettings {
    private fun prefs(context: Context) = context.getSharedPreferences("speech_input", Context.MODE_PRIVATE)
    fun enabled(context: Context) = (android.os.Build.VERSION.SDK_INT < 24 ||
        context.getSystemService(android.os.UserManager::class.java).isUserUnlocked) && prefs(context).getBoolean("enabled", true)
    fun setEnabled(context: Context, value: Boolean) { prefs(context).edit().putBoolean("enabled", value).apply() }
    fun provider(context: Context) = prefs(context).getString("provider", SpeechProviders.senseVoice.id)!!
    fun setProvider(context: Context, value: String) {
        require(SpeechProviders.available.any { it.id == value })
        prefs(context).edit().putString("provider", value).apply()
    }
    fun language(context: Context) = prefs(context).getString("language", "auto")!!
    fun setLanguage(context: Context, value: String) { prefs(context).edit().putString("language", value).apply() }
    fun wifiOnly(context: Context) = prefs(context).getBoolean("wifi_only", true)
    fun setWifiOnly(context: Context, value: Boolean) { prefs(context).edit().putBoolean("wifi_only", value).apply() }
    fun hasPermission(context: Context) = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    fun open(context: Context) { context.startActivity(Intent(context, SpeechActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}
