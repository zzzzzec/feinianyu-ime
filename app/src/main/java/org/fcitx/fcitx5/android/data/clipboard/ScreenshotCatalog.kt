// SPDX-License-Identifier: GPL-3.0-or-later
package org.fcitx.fcitx5.android.data.clipboard

import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import org.json.JSONObject
import java.util.Locale

data class ScreenshotRecord(val id: Long, val uri: Uri, val name: String, val path: String,
    val added: Long, val taken: Long, val size: Long, val pending: Boolean) {
    val timestamp get() = if (taken > 0) taken else added * 1000
}

object ScreenshotPolicy {
    fun matches(name: String, path: String): Boolean {
        val normalized = path.replace('\\', '/').trim('/').lowercase(Locale.ROOT)
        val folders = setOf("screenshot", "screenshots", "screen shots", "screen_shots", "screen captures", "screencapture", "screencaptures", "截图", "截屏", "屏幕截图")
        if (normalized.split('/').any { it in folders }) return true
        // Filename fallback for devices that save captures directly in Pictures.
        if (normalized.isNotEmpty() && normalized !in setOf("pictures", "dcim")) return false
        val lower = name.lowercase(Locale.ROOT)
        return listOf("screenshot", "screen_shot", "screen-shot", "screen capture", "screencapture", "截图", "截屏").any(lower::startsWith)
    }
}

data class ScreenshotCheckpoint(val enabledAt: Long, var retired: Long, val version: String, val seen: MutableSet<Long> = mutableSetOf()) {
    fun mark(id: Long) {
        seen.add(id)
        if (seen.size > 512) {
            val retained = seen.sortedDescending().take(512).toSet()
            retired = maxOf(retired, seen.filterNot { it in retained }.max())
            seen.retainAll(retained)
        }
    }
    fun encode() = JSONObject().put("enabled_at", enabledAt).put("retired", retired).put("version", version)
        .put("seen", org.json.JSONArray(seen.toList())).toString()
    companion object {
        fun decode(raw: String): ScreenshotCheckpoint = JSONObject(raw).let { json ->
            val ids = json.getJSONArray("seen")
            ScreenshotCheckpoint(json.getLong("enabled_at"), json.getLong("retired"), json.getString("version"),
                (0 until ids.length()).map { ids.getLong(it) }.toMutableSet())
        }
    }
}

object ScreenshotBatch {
    suspend fun capture(state: ScreenshotCheckpoint, rows: List<ScreenshotRecord>, limit: Int,
        import: suspend (ScreenshotRecord) -> Boolean): Boolean {
        var retry = false
        val ready = ArrayList<ScreenshotRecord>()
        rows.sortedBy { it.id }.forEach { row ->
            if (row.id <= state.retired || row.id in state.seen) return@forEach
            if (!ScreenshotPolicy.matches(row.name, row.path) || row.timestamp < state.enabledAt - 1000) state.mark(row.id)
            else if (row.pending || row.size <= 0) retry = true
            else ready.add(row)
        }
        // Older catch-up entries would immediately fall outside the history limit.
        val selected = ready.takeLast(limit.coerceAtLeast(1))
        ready.dropLast(selected.size).forEach { state.mark(it.id) }
        selected.forEach { if (import(it)) state.mark(it.id) else retry = true }
        return retry
    }
}

class ScreenshotCatalog(private val context: Context) {
    val collection: Uri get() = if (Build.VERSION.SDK_INT >= 29) MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
    fun version(): String = if (Build.VERSION.SDK_INT >= 29) MediaStore.getVersion(context) else Build.FINGERPRINT

    private fun query(projection: Array<String>, selection: String?, args: Array<String>?, limit: Int): android.database.Cursor? {
        val resolver = context.contentResolver
        return if (Build.VERSION.SDK_INT >= 26) resolver.query(collection, projection, Bundle().apply {
            putString(ContentResolver.QUERY_ARG_SQL_SELECTION, selection)
            putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, args)
            putString(ContentResolver.QUERY_ARG_SQL_SORT_ORDER, "${MediaStore.Images.Media._ID} DESC")
            putInt(ContentResolver.QUERY_ARG_LIMIT, limit)
        }, null) else resolver.query(collection, projection, selection, args, "${MediaStore.Images.Media._ID} DESC")
    }

    fun latestId(): Long = query(arrayOf(MediaStore.Images.Media._ID), null, null, 1)?.use { if (it.moveToFirst()) it.getLong(0) else 0 } ?: 0

    fun recent(state: ScreenshotCheckpoint): List<ScreenshotRecord> {
        val path = if (Build.VERSION.SDK_INT >= 29) MediaStore.Images.Media.RELATIVE_PATH else MediaStore.Images.Media.DATA
        val projection = mutableListOf(MediaStore.Images.Media._ID, MediaStore.Images.Media.DISPLAY_NAME, path,
            MediaStore.Images.Media.DATE_ADDED, MediaStore.Images.Media.DATE_TAKEN, MediaStore.Images.Media.SIZE)
        if (Build.VERSION.SDK_INT >= 29) projection.add(MediaStore.Images.Media.IS_PENDING)
        val keywords = listOf("%screenshot%", "%screen_shot%", "%screen-shot%", "%screen capture%", "%screencapture%", "%截图%", "%截屏%")
        val filter = keywords.joinToString(" OR ") { "$path LIKE ? OR ${MediaStore.Images.Media.DISPLAY_NAME} LIKE ?" }
        val args = arrayOf(state.retired.toString(), (state.enabledAt / 1000).toString(), *keywords.flatMap { listOf(it, it) }.toTypedArray())
        val result = ArrayList<ScreenshotRecord>()
        query(projection.toTypedArray(), "${MediaStore.Images.Media._ID}>? AND ${MediaStore.Images.Media.DATE_ADDED}>=? AND ($filter)", args, 64)?.use { cursor ->
            while (result.size < 64 && cursor.moveToNext()) {
                val id = cursor.getLong(0)
                result.add(ScreenshotRecord(id, ContentUris.withAppendedId(collection, id), cursor.getString(1).orEmpty(), cursor.getString(2).orEmpty(),
                    cursor.getLong(3), cursor.getLong(4), cursor.getLong(5), Build.VERSION.SDK_INT >= 29 && cursor.getInt(6) != 0))
            }
        }
        return result
    }
}
