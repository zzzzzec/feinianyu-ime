// SPDX-License-Identifier: LGPL-2.1-or-later
package org.fcitx.fcitx5.android

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.fcitx.fcitx5.android.data.clipboard.db.ClipboardDatabase
import org.fcitx.fcitx5.android.data.prefs.ConfigMigrations
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID

class UpdateRetentionTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun configurationUpgradePreservesChoicesAndCanRestore() {
        val name = "migration-test-${UUID.randomUUID()}"
        val preferences = context.getSharedPreferences(name, Context.MODE_PRIVATE)
        val directory = File(context.cacheDir, name)
        try {
            assertTrue(preferences.edit().putInt("keyboard_font_size", 24)
                .putBoolean("suzu_enabled", false).putString("light_mode_theme", "MyOwnTheme")
                .putStringSet("unknown_future_setting", setOf("one", "two"))
                .putLong("long_value", 9876543210L).putFloat("float_value", 1.25f).commit())
            ConfigMigrations.migrate(preferences, directory)
            assertEquals(24, preferences.getInt("keyboard_font_size", 0))
            assertFalse(preferences.getBoolean("suzu_enabled", true))
            assertEquals("MyOwnTheme", preferences.getString("light_mode_theme", null))
            assertEquals(18, preferences.getInt("keyboard_candidate_font_size", 0))
            assertEquals(ConfigMigrations.CURRENT_VERSION, preferences.getInt(ConfigMigrations.VERSION_KEY, 0))
            val snapshot = File(directory, "preferences-v0.json")
            val originalBackup = snapshot.readText()
            preferences.edit().putInt("keyboard_font_size", 17).commit()
            ConfigMigrations.migrate(preferences, directory)
            assertEquals(17, preferences.getInt("keyboard_font_size", 0))
            assertEquals(originalBackup, snapshot.readText())
            ConfigMigrations.restore(preferences, snapshot)
            assertEquals(24, preferences.getInt("keyboard_font_size", 0))
            assertFalse(preferences.contains("keyboard_candidate_font_size"))
            assertEquals(setOf("one", "two"), preferences.getStringSet("unknown_future_setting", null))
            assertEquals(9876543210L, preferences.getLong("long_value", 0))
            assertEquals(1.25f, preferences.getFloat("float_value", 0f), 0.001f)
            // A retry after restoring/editing an older schema must snapshot the
            // latest choices, not reuse the first attempt's stale backup.
            preferences.edit().putInt("keyboard_font_size", 25).commit()
            ConfigMigrations.migrate(preferences, directory)
            ConfigMigrations.restore(preferences, snapshot)
            assertEquals(25, preferences.getInt("keyboard_font_size", 0))
        } finally { preferences.edit().clear().commit(); directory.deleteRecursively() }
    }

    @Test
    fun failedBackupDoesNotPartiallyMigrate() {
        val name = "migration-failure-${UUID.randomUUID()}"
        val preferences = context.getSharedPreferences(name, Context.MODE_PRIVATE)
        val blockedDirectory = File(context.cacheDir, name).apply { writeText("synthetic blocker") }
        try {
            preferences.edit().putInt("keyboard_font_size", 22).commit()
            assertTrue(runCatching { ConfigMigrations.migrate(preferences, blockedDirectory) }.isFailure)
            assertEquals(22, preferences.getInt("keyboard_font_size", 0))
            assertFalse(preferences.contains(ConfigMigrations.VERSION_KEY))
            assertFalse(preferences.contains("keyboard_candidate_font_size"))
        } finally { preferences.edit().clear().commit(); blockedDirectory.delete() }
    }

    @Test
    fun clipboardVersionOneMigratesWithoutLosingRows(): Unit = runBlocking {
        val name = "clipboard-upgrade-${UUID.randomUUID()}.db"
        val path = context.getDatabasePath(name).apply { parentFile?.mkdirs() }
        val instrumentation = InstrumentationRegistry.getInstrumentation().context
        val schema = JSONObject(instrumentation.assets.open("org.fcitx.fcitx5.android.data.clipboard.db.ClipboardDatabase/1.json")
            .bufferedReader().use { it.readText() }).getJSONObject("database")
        try {
            SQLiteDatabase.openOrCreateDatabase(path, null).use { database ->
                val entities = schema.getJSONArray("entities")
                for (i in 0 until entities.length()) {
                    val entity = entities.getJSONObject(i)
                    database.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", entity.getString("tableName")))
                }
                val setup = schema.getJSONArray("setupQueries")
                for (i in 0 until setup.length()) database.execSQL(setup.getString(i))
                database.execSQL("INSERT INTO clipboard (id, text, pinned) VALUES (1, 'synthetic retained clipboard', 1)")
                database.version = 1
            }
            val database = Room.databaseBuilder(context, ClipboardDatabase::class.java, name).build()
            try {
                val entry = database.clipboardDao().get(1)
                assertEquals("synthetic retained clipboard", entry?.text)
                assertEquals(true, entry?.pinned)
                assertEquals(4, database.openHelper.readableDatabase.version)
            } finally { database.close() }
        } finally { context.deleteDatabase(name) }
    }
}
