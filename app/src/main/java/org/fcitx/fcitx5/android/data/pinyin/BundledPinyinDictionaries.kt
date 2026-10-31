// SPDX-License-Identifier: GPL-3.0-or-later
package org.fcitx.fcitx5.android.data.pinyin

import android.content.Context
import android.os.Build
import android.os.UserManager
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import timber.log.Timber

/** First install only. User disable/delete choices and personal dictionaries survive upgrades. */
object BundledPinyinDictionaries {
    @Synchronized fun install(context: Context) {
        if (Build.VERSION.SDK_INT >= 24 && !context.getSystemService(UserManager::class.java).isUserUnlocked) return
        val preferences = context.getSharedPreferences("bundled_pinyin_dictionaries", Context.MODE_PRIVATE)
        val folder = File(context.getExternalFilesDir(null) ?: return, "data/pinyin/dictionaries").apply { mkdirs() }
        runCatching {
            val packs = JSONObject(context.assets.open("dictionaries/manifest.json").bufferedReader().use { it.readText() }).getJSONArray("pinyin")
            for (i in 0 until packs.length()) {
                val pack = packs.getJSONObject(i); val id = pack.getString("id")
                if (preferences.getBoolean(id, false)) continue
                val target = File(folder, pack.getString("name") + ".dict")
                if (!target.exists() && !File(target.path + ".disable").exists()) {
                    val source = File(context.cacheDir, "bundled-$id.txt")
                    val output = File(folder, "bundled-$id.tmp")
                    try {
                        val bytes = context.assets.open("dictionaries/" + pack.getString("asset")).use { it.readBytes() }
                        check(MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) } == pack.getString("sha256"))
                        source.writeBytes(bytes)
                        PinyinDictManager.pinyinDictConv(source.absolutePath, output.absolutePath, PinyinDictManager.MODE_TXT_TO_BIN)
                        check(output.length() > 0 && output.renameTo(target))
                    } finally { source.delete(); output.delete() }
                }
                preferences.edit().putBoolean(id, true).commit()
            }
        }.onFailure { Timber.e(it, "Cannot install bundled pinyin dictionaries") }
    }
}
