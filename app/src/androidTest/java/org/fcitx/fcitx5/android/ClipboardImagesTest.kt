// SPDX-License-Identifier: GPL-3.0-or-later
package org.fcitx.fcitx5.android

import android.content.res.AssetFileDescriptor
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.view.View
import android.view.inputmethod.*
import androidx.core.view.inputmethod.InputConnectionCompat
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.fcitx.fcitx5.android.data.clipboard.*
import org.fcitx.fcitx5.android.data.clipboard.db.*
import org.fcitx.fcitx5.android.fixture.ImageFixture
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.util.UUID

class ClipboardImagesTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    private fun fixture(test: suspend (ClipboardRepository, ClipboardDatabase, ClipboardImageStore, File) -> Unit): Unit = runBlocking {
        val directory = File(context.filesDir, "clipboard-images/test-${UUID.randomUUID()}").apply { mkdirs() }
        val store = ClipboardImageStore(context, directory)
        val database = Room.inMemoryDatabaseBuilder(context, ClipboardDatabase::class.java).build()
        val repository = ClipboardRepository(database, store, { 2 }, {})
        try { repository.initialize(); test(repository, database, store, directory) }
        finally { database.close(); directory.listFiles()?.forEach { it.delete() }; directory.delete() }
    }

    private fun opened(file: File) = ClipboardImageStore.Opened(AssetFileDescriptor(
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY), 0, file.length()))

    @Test fun keepsOriginalBytesWhenSourceDisappearsAndDeduplicates() = fixture { repository, _, store, directory ->
        val source = File(directory, "source.png").apply { writeBytes(ImageFixture.png()) }
        val descriptor = opened(source)
        assertTrue(source.delete())
        repository.capture(ClipboardEntry(text = "", timestamp = 10), descriptor)
        val first = repository.lastEntry!!
        assertTrue(first.isImage)
        assertEquals("image/png", first.type)
        assertArrayEquals(ImageFixture.png(), store.file(first.imageFile!!).readBytes())
        assertNotNull(store.thumbnail(first.imageFile))
        val second = File(directory, "another.png").apply { writeBytes(ImageFixture.png()) }
        repository.capture(ClipboardEntry(text = "", timestamp = 20), opened(second))
        second.delete()
        assertEquals(first.id, repository.lastEntry!!.id)
        assertEquals(1, repository.itemCount)
    }

    @Test fun deletionCanBeUndoneAndSharedFilesRemainUntilLastReferenceIsGone() = fixture { repository, database, store, _ ->
        val image = store.save(ByteArrayInputStream(ImageFixture.png()))
        repository.capture(ClipboardEntry(text = "", type = image.mime, imageFile = image.file, timestamp = 1))
        val first = repository.lastEntry!!
        repository.capture(first.copy(id = 0, sensitive = true, timestamp = 2))
        val second = repository.lastEntry!!
        assertNotEquals(first.id, second.id)
        repository.pin(second.id, true)
        repository.delete(first.id)
        assertTrue(store.file(image.file).exists())
        repository.undoDelete(first.id)
        assertNotNull(database.clipboardDao().get(first.id))
        repository.delete(first.id); repository.realDelete()
        assertTrue(store.file(image.file).exists())
        repository.deleteAll(skipPinned = true); repository.realDelete()
        assertNotNull(database.clipboardDao().get(second.id))
        repository.delete(second.id); repository.realDelete()
        assertFalse(store.file(image.file).exists())
    }

    @Test fun historyTrimmingPreservesPinnedImagesAndZeroLimitKeepsOnlyLatestSuggestion() = fixture { _, database, store, _ ->
        var limit = 1
        val repository = ClipboardRepository(database, store, { limit }, {})
        val image = store.save(ByteArrayInputStream(ImageFixture.png()))
        repository.capture(ClipboardEntry(text = "", type = image.mime, imageFile = image.file, timestamp = 1))
        val id = repository.lastEntry!!.id
        repository.pin(id, true)
        repository.capture(ClipboardEntry(text = "new text", timestamp = 2))
        repository.capture(ClipboardEntry(text = "newest text", timestamp = 3))
        assertTrue(store.file(image.file).exists())
        assertNotNull(database.clipboardDao().get(id))
        repository.pin(id, false)
        assertFalse(store.file(image.file).exists())
        limit = 0
        val again = store.save(ByteArrayInputStream(ImageFixture.png()))
        repository.capture(ClipboardEntry(text = "", type = again.mime, imageFile = again.file, timestamp = 4))
        assertEquals(0, repository.itemCount)
        assertTrue(store.file(again.file).exists())
        repository.capture(ClipboardEntry(text = "replacement", timestamp = 5))
        assertFalse(store.file(again.file).exists())
    }

    @Test fun invalidAndOversizedSourcesLeaveNoPartialImages() = fixture { repository, _, store, directory ->
        repository.capture(ClipboardEntry(text = "retained text"))
        try { store.save(ByteArrayInputStream("not an image".toByteArray())); fail("Invalid image accepted") } catch (_: Exception) {}
        val oversized = object : InputStream() {
            var remaining = ClipboardImageStore.MAX_BYTES + 1
            override fun read(): Int = if (remaining-- > 0) 0 else -1
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                if (remaining == 0) return -1
                val count = minOf(length, remaining); buffer.fill(0, offset, offset + count); remaining -= count; return count
            }
        }
        try { store.save(oversized); fail("Oversized image accepted") } catch (_: ClipboardImageStore.TooLarge) {}
        assertEquals(0, directory.listFiles()!!.size)
        assertEquals("retained text", repository.lastEntry!!.text)
    }

    @Test fun systemClipboardReferenceOutlivesHistoryDeletion() = fixture { _, database, store, _ ->
        var primary = emptySet<String>()
        val repository = ClipboardRepository(database, store, { 2 }, {}, { primary })
        val image = store.save(ByteArrayInputStream(ImageFixture.png()))
        repository.capture(ClipboardEntry(text = "", type = image.mime, imageFile = image.file))
        primary = setOf(image.file)
        repository.delete(repository.lastEntry!!.id); repository.realDelete()
        assertTrue(store.file(image.file).exists())
        primary = emptySet()
        repository.trim()
        assertFalse(store.file(image.file).exists())
    }

    @Test fun olderScreenshotCatchUpPreservesLatestClipboardAndDuplicateTimestamp() = fixture { repository, database, store, _ ->
        repository.capture(ClipboardEntry(text = "latest copied text", timestamp = 100))
        val latest = repository.lastEntry!!
        val image = store.save(ByteArrayInputStream(ImageFixture.png()))
        val screenshot = ClipboardEntry(text = "", type = image.mime, imageFile = image.file, timestamp = 50)
        repository.capture(screenshot, promoteToLast = false)
        assertEquals(latest, repository.lastEntry)
        val saved = database.clipboardDao().findImage(image.file, false)!!
        repository.capture(screenshot.copy(timestamp = 20), promoteToLast = false)
        assertEquals(50L, database.clipboardDao().get(saved.id)!!.timestamp)
        assertEquals(latest, repository.lastEntry)
        assertTrue(store.file(image.file).exists())
        assertEquals(2, repository.itemCount)
    }

    @Test fun commitUsesImageMimeAndTemporaryReadGrantAndRejectsPlainTextEditors() = fixture { _, _, store, _ ->
        val saved = store.save(ByteArrayInputStream(ImageFixture.png()))
        val entry = ClipboardEntry(text = "", type = saved.mime, imageFile = saved.file)
        val clip = ClipboardContent.imageClip(context, entry, store)
        assertEquals("", clip.getItemAt(0).coerceToText(context).toString())
        assertEquals(store.uri(saved.file), clip.getItemAt(0).uri)
        var calls = 0
        val connection = object : BaseInputConnection(View(context), false) {
            override fun commitContent(info: InputContentInfo, flags: Int, opts: Bundle?): Boolean {
                calls++
                assertEquals("image/png", info.description.getMimeType(0))
                assertEquals(store.uri(saved.file), info.contentUri)
                assertEquals(InputConnectionCompat.INPUT_CONTENT_GRANT_READ_URI_PERMISSION, flags)
                return true
            }
        }
        val editor = EditorInfo().apply { packageName = context.packageName; contentMimeTypes = arrayOf("image/*") }
        assertTrue(ClipboardContent.commit(context, connection, editor, entry, store))
        editor.contentMimeTypes = arrayOf("text/plain")
        assertFalse(ClipboardContent.commit(context, connection, editor, entry, store))
        assertEquals(1, calls)
        try { store.file("../private.png"); fail("Path traversal accepted") } catch (_: IllegalArgumentException) {}
    }
}
