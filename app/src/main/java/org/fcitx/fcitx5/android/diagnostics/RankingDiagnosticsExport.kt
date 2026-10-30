// SPDX-License-Identifier: GPL-3.0-or-later
package org.fcitx.fcitx5.android.diagnostics

import android.os.Build
import org.fcitx.fcitx5.android.BuildConfig
import org.json.JSONObject

object RankingDiagnosticsExport {
    fun create(snapshot: String): String = JSONObject().apply {
        put("export_schema_version", 1)
        put("exported_at_ms", System.currentTimeMillis())
        put("app_id", BuildConfig.APPLICATION_ID)
        put("app_version", BuildConfig.VERSION_NAME)
        put("android_sdk", Build.VERSION.SDK_INT)
        put("abi", Build.SUPPORTED_ABIS.firstOrNull() ?: "")
        put("diagnostics", JSONObject(snapshot))
    }.toString(2)
}
