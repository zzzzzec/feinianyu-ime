// SPDX-License-Identifier: GPL-3.0-or-later
package org.fcitx.fcitx5.android

import android.net.Uri
import kotlinx.coroutines.runBlocking
import org.fcitx.fcitx5.android.data.clipboard.*
import org.junit.Assert.*
import org.junit.Test

class ScreenshotCaptureTest {
    private fun shot(id: Long, path: String = "Pictures/Screenshots/", pending: Boolean = false, size: Long = 100) =
        ScreenshotRecord(id, Uri.parse("content://media/external/images/media/$id"), "Screenshot_test.png", path, 100, 100_500, size, pending)

    @Test fun recognizesScreenshotFoldersWithoutImportingDownloadsOrCameraPhotos() {
        assertTrue(ScreenshotPolicy.matches("Screenshot_123.png", "DCIM/Screenshots/"))
        assertTrue(ScreenshotPolicy.matches("screen.png", "Pictures/屏幕截图/"))
        assertTrue(ScreenshotPolicy.matches("Screenshot.png", ""))
        assertFalse(ScreenshotPolicy.matches("Screenshot.png", "Download/"))
        assertFalse(ScreenshotPolicy.matches("IMG_123.jpg", "DCIM/Camera/"))
    }

    @Test fun startsAfterEnableAndRemembersAlreadyImportedRowsAcrossRestart(): Unit = runBlocking {
        val state = ScreenshotCheckpoint(100_000, 10, "test-version")
        val imported = mutableListOf<Long>()
        ScreenshotBatch.capture(state, listOf(shot(9), shot(10), shot(11), shot(12, "Download/")), 10) { imported.add(it.id); true }
        assertEquals(listOf(11L), imported)
        val restored = ScreenshotCheckpoint.decode(state.encode())
        ScreenshotBatch.capture(restored, listOf(shot(11), shot(12, "Download/"), shot(13)), 10) { imported.add(it.id); true }
        assertEquals(listOf(11L, 13L), imported)
    }

    @Test fun waitsForPendingImagesAndRetriesFailedReads(): Unit = runBlocking {
        val state = ScreenshotCheckpoint(100_000, 1, "test-version")
        val imported = mutableListOf<Long>()
        assertTrue(ScreenshotBatch.capture(state, listOf(shot(2, pending = true), shot(3)), 10) { imported.add(it.id); true })
        assertEquals(listOf(3L), imported)
        assertTrue(ScreenshotBatch.capture(state, listOf(shot(2)), 10) { false })
        assertFalse(2L in state.seen)
        ScreenshotBatch.capture(state, listOf(shot(2)), 10) { imported.add(it.id); true }
        assertEquals(listOf(3L, 2L), imported)
    }

    @Test fun catchUpKeepsNewestHistoryAndRetiredIdsNeverReappear(): Unit = runBlocking {
        val state = ScreenshotCheckpoint(100_000, 1, "test-version")
        val imported = mutableListOf<Long>()
        ScreenshotBatch.capture(state, (2L..20L).map { shot(it) }, 3) { imported.add(it.id); true }
        assertEquals(listOf(18L, 19L, 20L), imported)
        (21L..700L).forEach(state::mark)
        assertTrue(state.seen.size <= 512)
        val restored = ScreenshotCheckpoint.decode(state.encode())
        ScreenshotBatch.capture(restored, listOf(shot(2)), 3) { fail("Retired screenshot re-imported"); true }
    }
}
