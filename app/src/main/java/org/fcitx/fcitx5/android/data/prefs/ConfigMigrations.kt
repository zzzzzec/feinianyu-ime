// SPDX-License-Identifier: LGPL-2.1-or-later
package org.fcitx.fcitx5.android.data.prefs

import android.content.SharedPreferences
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Increment this independently of APK and Room versions. Never reset user data. */
object ConfigMigrations {
    const val CURRENT_VERSION = 1
    const val VERSION_KEY = "config_schema_version"

    @Synchronized
    fun migrate(preferences: SharedPreferences, backupDirectory: File) {
        val oldVersion = preferences.getInt(VERSION_KEY, 0)
        if (oldVersion >= CURRENT_VERSION) return
        // Persist the exact previous values, with types, before any edit.
        check(backupDirectory.isDirectory || backupDirectory.mkdirs()) { "Cannot create config backup directory" }
        val snapshot = File(backupDirectory, "preferences-v$oldVersion.json")
        if (!snapshot.exists()) {
            val values = JSONObject()
            preferences.all.forEach { (key, value) ->
                val type = when (value) {
                    is String -> "string"
                    is Boolean -> "boolean"
                    is Int -> "int"
                    is Long -> "long"
                    is Float -> "float"
                    is Set<*> -> "set"
                    else -> error("Unsupported preference type")
                }
                values.put(key, JSONObject().put("type", type).put("value", if (value is Set<*>) JSONArray(value.toList()) else value))
            }
            val bytes = JSONObject().put("schemaVersion", oldVersion).put("values", values).toString().toByteArray(Charsets.UTF_8)
            val atomic = AtomicFile(snapshot)
            val output = atomic.startWrite()
            try { output.write(bytes); atomic.finishWrite(output) }
            catch (error: Throwable) { atomic.failWrite(output); throw error }
        }
        val edit = preferences.edit()
        if (oldVersion < 1) {
            // Only missing keys get defaults. Existing choices always win.
            if (!preferences.contains("suzu_enabled")) edit.putBoolean("suzu_enabled", true)
            if (!preferences.contains("keyboard_font_size")) edit.putInt("keyboard_font_size", 20)
            if (!preferences.contains("keyboard_candidate_font_size")) edit.putInt("keyboard_candidate_font_size", 18)
        }
        check(edit.putInt(VERSION_KEY, CURRENT_VERSION).commit()) { "Cannot persist config migration" }
    }

    /** Explicit recovery of configuration only; never touches dictionaries or databases. */
    @Synchronized
    fun restore(preferences: SharedPreferences, snapshot: File) {
        val values = JSONObject(AtomicFile(snapshot).openRead().bufferedReader().use { it.readText() }).getJSONObject("values")
        val edit = preferences.edit().clear()
        for (key in values.keys()) {
            val entry = values.getJSONObject(key)
            when (entry.getString("type")) {
                "string" -> edit.putString(key, entry.getString("value"))
                "boolean" -> edit.putBoolean(key, entry.getBoolean("value"))
                "int" -> edit.putInt(key, entry.getInt("value"))
                "long" -> edit.putLong(key, entry.getLong("value"))
                "float" -> edit.putFloat(key, entry.getDouble("value").toFloat())
                "set" -> entry.getJSONArray("value").let { array -> edit.putStringSet(key, (0 until array.length()).map { array.getString(it) }.toSet()) }
                else -> error("Unsupported backup preference type")
            }
        }
        check(edit.commit()) { "Cannot restore configuration" }
    }
}
