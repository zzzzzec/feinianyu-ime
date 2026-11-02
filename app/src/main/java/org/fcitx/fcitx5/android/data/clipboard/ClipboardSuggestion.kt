// SPDX-License-Identifier: GPL-3.0-or-later
package org.fcitx.fcitx5.android.data.clipboard

import org.fcitx.fcitx5.android.data.clipboard.db.ClipboardEntry
import java.security.MessageDigest

object ClipboardSuggestion {
    fun key(entry: ClipboardEntry): String {
        val payload = "${entry.timestamp}\n${entry.type}\n${entry.imageFile.orEmpty()}\n${entry.text}"
        return MessageDigest.getInstance("SHA-256").digest(payload.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    fun remainingMillis(timestamp: Long, timeoutSeconds: Int, now: Long = System.currentTimeMillis()): Long {
        if (timeoutSeconds < 0) return Long.MAX_VALUE
        return (timeoutSeconds * 1000L - (now - timestamp).coerceAtLeast(0)).coerceAtLeast(0)
    }
}
