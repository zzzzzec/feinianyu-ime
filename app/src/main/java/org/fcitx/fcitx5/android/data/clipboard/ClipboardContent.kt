// SPDX-License-Identifier: GPL-3.0-or-later
package org.fcitx.fcitx5.android.data.clipboard

import android.content.ClipData
import android.content.ClipDescription
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PersistableBundle
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import androidx.core.view.inputmethod.EditorInfoCompat
import androidx.core.view.inputmethod.InputConnectionCompat
import androidx.core.view.inputmethod.InputContentInfoCompat
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.clipboard.db.ClipboardEntry
import org.fcitx.fcitx5.android.utils.clipboardManager

object ClipboardContent {
    fun supports(info: EditorInfo, mime: String) = EditorInfoCompat.getContentMimeTypes(info).any { ClipDescription.compareMimeTypes(mime, it) }

    fun commit(context: Context, connection: InputConnection, info: EditorInfo, entry: ClipboardEntry, images: ClipboardImageStore = ClipboardImages.store): Boolean {
        if (!entry.isImage || !supports(info, entry.type)) return false
        val uri = images.uri(entry.imageFile!!)
        val legacy = Build.VERSION.SDK_INT < 25
        if (legacy) context.grantUriPermission(info.packageName, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        val content = InputContentInfoCompat(uri, ClipDescription(context.getString(R.string.clipboard_image), arrayOf(entry.type)), null)
        var accepted = false
        try {
            accepted = InputConnectionCompat.commitContent(connection, info, content,
                if (legacy) 0 else InputConnectionCompat.INPUT_CONTENT_GRANT_READ_URI_PERMISSION, null)
            return accepted
        } finally {
            if (legacy && !accepted) context.revokeUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    fun imageClip(context: Context, entry: ClipboardEntry, images: ClipboardImageStore = ClipboardImages.store): ClipData {
        // Plain-text editors must not coerce a private content URI into text.
        val clip = ClipData(context.getString(R.string.clipboard_image), arrayOf(entry.type),
            ClipData.Item("", null, images.uri(entry.imageFile!!)))
        if (Build.VERSION.SDK_INT >= 24 && entry.sensitive) clip.description.extras = PersistableBundle().apply { putBoolean("android.content.extra.IS_SENSITIVE", true) }
        return clip
    }

    fun copy(context: Context, entry: ClipboardEntry) {
        val clip = imageClip(context, entry)
        context.clipboardManager.setPrimaryClip(clip)
        ClipboardImages.primaryFile = entry.imageFile
    }

    fun share(context: Context, entry: ClipboardEntry): Intent = Intent(Intent.ACTION_SEND).apply {
        type = if (entry.isImage) entry.type else "text/plain"
        if (entry.isImage) {
            val uri = ClipboardImages.store.uri(entry.imageFile!!)
            putExtra(Intent.EXTRA_STREAM, uri)
            clipData = ClipData.newRawUri(context.getString(R.string.clipboard_image), uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } else putExtra(Intent.EXTRA_TEXT, entry.text)
    }
}
