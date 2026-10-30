// SPDX-License-Identifier: LGPL-2.1-or-later
package org.fcitx.fcitx5.android

import android.content.Context
import androidx.preference.PreferenceManager
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.fcitx.fcitx5.android.data.clipboard.db.ClipboardDatabase
import org.fcitx.fcitx5.android.data.clipboard.db.ClipboardEntry
import org.fcitx.fcitx5.android.data.prefs.ConfigMigrations
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/** Explicit two-process, two-APK test. Not run by the normal test suite. */
class UpgradeFixtureTest {
    @Test fun retainsDataAcrossApkUpgrade(): Unit = runBlocking {
        val phase = InstrumentationRegistry.getArguments().getString("upgradePhase")
        assumeTrue(phase == "seed" || phase == "verify")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val preferences = PreferenceManager.getDefaultSharedPreferences(context)
        val state = context.getSharedPreferences("upgrade_fixture", Context.MODE_PRIVATE)
        val file = File(context.filesDir, "upgrade-retention-fixture.txt")
        val database = Room.databaseBuilder(context, ClipboardDatabase::class.java, "clbdb").build()
        try {
            if (phase == "seed") {
                val id = database.clipboardDao().insert(ClipboardEntry(text = "Feinianyu synthetic upgrade fixture", pinned = true))
                state.edit().putLong("row", id).commit()
                file.writeText("retain local user file across APK replacement")
                assertTrue(preferences.edit().putInt("keyboard_font_size", 17).putBoolean("suzu_enabled", false)
                    .putInt(ConfigMigrations.VERSION_KEY, 0).remove("keyboard_candidate_font_size").commit())
            } else {
                assertEquals(17, preferences.getInt("keyboard_font_size", 0))
                assertFalse(preferences.getBoolean("suzu_enabled", true))
                assertEquals(18, preferences.getInt("keyboard_candidate_font_size", 0))
                assertEquals(ConfigMigrations.CURRENT_VERSION, preferences.getInt(ConfigMigrations.VERSION_KEY, 0))
                val entry = database.clipboardDao().get(state.getLong("row", -1))
                assertEquals("Feinianyu synthetic upgrade fixture", entry?.text)
                assertEquals(true, entry?.pinned)
                assertEquals("retain local user file across APK replacement", file.readText())
                println("UPGRADE_RETENTION_OK configuration clipboard local-file")
                database.openHelper.writableDatabase.execSQL("DELETE FROM clipboard WHERE id=?", arrayOf(entry!!.id))
                file.delete()
                state.edit().clear().commit()
                preferences.edit().putInt("keyboard_font_size", 20).putBoolean("suzu_enabled", true).commit()
            }
        } finally { database.close() }
    }
}
