// SPDX-License-Identifier: GPL-3.0-or-later
package org.fcitx.fcitx5.android.update

import org.json.JSONObject

data class ReleaseAsset(val name: String, val abi: String, val versionCode: Long, val size: Long, val sha256: String)

object ReleaseManifest {
    fun select(json: String, applicationId: String, supportedAbis: List<String>, currentVersion: Long): ReleaseAsset? {
        val root = JSONObject(json)
        require(root.getInt("schemaVersion") == 1) { "Unsupported update manifest" }
        require(root.getString("applicationId") == applicationId) { "Update belongs to another app" }
        val entries = root.getJSONArray("assets")
        val assets = (0 until entries.length()).map { index ->
            val entry = entries.getJSONObject(index)
            ReleaseAsset(entry.getString("name"), entry.getString("abi"), entry.getLong("versionCode"),
                entry.getLong("size"), entry.getString("sha256").lowercase()).also {
                require(it.name.matches(Regex("[A-Za-z0-9._-]+\\.apk"))) { "Invalid APK asset name" }
                require(it.sha256.matches(Regex("[a-f0-9]{64}"))) { "Invalid APK digest" }
                require(it.size in 1..314572800L && it.versionCode > 0) { "Invalid APK size or version" }
            }
        }
        return supportedAbis.firstNotNullOfOrNull { abi -> assets.firstOrNull { it.abi == abi && it.versionCode > currentVersion } }
    }
}
