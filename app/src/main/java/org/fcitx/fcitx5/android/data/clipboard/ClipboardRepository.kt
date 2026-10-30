// SPDX-License-Identifier: GPL-3.0-or-later
package org.fcitx.fcitx5.android.data.clipboard

import androidx.room.withTransaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.fcitx.fcitx5.android.data.clipboard.db.ClipboardDatabase
import org.fcitx.fcitx5.android.data.clipboard.db.ClipboardEntry

class ClipboardRepository(
    private val database: ClipboardDatabase,
    private val images: ClipboardImageStore,
    private val historyLimit: () -> Int,
    private val onUpdate: (ClipboardEntry?) -> Unit,
    private val additionalReferences: () -> Set<String> = { emptySet() }
) {
    private val dao = database.clipboardDao()
    private val mutex = Mutex()
    @Volatile var lastEntry: ClipboardEntry? = null
        private set
    @Volatile var itemCount = 0
        private set

    private suspend fun cleanUp() {
        images.cleanUp((dao.imageFiles() + listOfNotNull(lastEntry?.imageFile) + additionalReferences()).toSet())
        itemCount = dao.itemCount()
    }

    private fun update(entry: ClipboardEntry?) { lastEntry = entry; onUpdate(entry) }

    suspend fun initialize() = withContext(Dispatchers.IO) {
        mutex.withLock { dao.realDelete(); cleanUp() }
    }

    suspend fun trim() = withContext(Dispatchers.IO) {
        mutex.withLock { database.withTransaction { trimRows() }; cleanUp() }
    }

    private suspend fun trimRows() {
        val obsolete = dao.getAllUnpinned().sortedWith(compareByDescending<ClipboardEntry> { it.timestamp }.thenByDescending { it.id })
            .drop(historyLimit().coerceAtLeast(0)).map { it.id }.toIntArray()
        if (obsolete.isNotEmpty()) dao.deleteEntries(obsolete)
    }

    suspend fun capture(entry: ClipboardEntry, source: ClipboardImageStore.Opened? = null) = withContext(Dispatchers.IO) {
        mutex.withLock {
            try {
                val prepared = if (source == null) entry else source.use {
                    if (it.descriptor.length > ClipboardImageStore.MAX_BYTES) throw ClipboardImageStore.TooLarge()
                    val stored = it.descriptor.createInputStream().use(images::save)
                    entry.copy(text = "", type = stored.mime, imageFile = stored.file)
                }
                if (!prepared.isImage && prepared.text.isBlank()) return@withLock
                val duplicate = if (prepared.isImage) dao.findImage(prepared.imageFile!!, prepared.sensitive) else dao.find(prepared.text, prepared.sensitive)
                val saved = database.withTransaction {
                    val value = if (duplicate != null) {
                        dao.updateTime(duplicate.id, prepared.timestamp)
                        duplicate.copy(timestamp = prepared.timestamp)
                    } else prepared.copy(id = dao.insert(prepared).toInt())
                    trimRows()
                    value
                }
                update(saved)
            } finally { source?.close(); cleanUp() }
        }
    }

    suspend fun updateText(id: Int, text: String) = withContext(Dispatchers.IO) {
        mutex.withLock {
            dao.updateText(id, text)
            lastEntry?.takeIf { it.id == id && !it.isImage }?.let { update(it.copy(text = text)) }
        }
    }

    suspend fun pin(id: Int, value: Boolean) = withContext(Dispatchers.IO) {
        mutex.withLock { dao.updatePinStatus(id, value); if (!value) trimRows(); cleanUp() }
    }

    suspend fun delete(id: Int) = withContext(Dispatchers.IO) {
        mutex.withLock {
            dao.markAsDeleted(id)
            if (lastEntry?.id == id) update(null)
            cleanUp()
        }
    }

    suspend fun deleteAll(skipPinned: Boolean) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val ids = if (skipPinned) dao.findUnpinnedIds() else dao.findAllIds()
            dao.markAsDeleted(*ids)
            if (lastEntry?.id?.let { it in ids } == true) update(null)
            cleanUp()
            ids
        }
    }

    suspend fun undoDelete(vararg ids: Int) = withContext(Dispatchers.IO) { mutex.withLock { dao.undoDelete(*ids); cleanUp() } }
    suspend fun realDelete() = withContext(Dispatchers.IO) { mutex.withLock { dao.realDelete(); cleanUp() } }
    suspend fun clear() = withContext(Dispatchers.IO) { mutex.withLock { database.clearAllTables(); update(null); cleanUp() } }
}
