// SPDX-License-Identifier: GPL-3.0-or-later
package org.fcitx.fcitx5.android

import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.fcitx.fcitx5.android.speech.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class SpeechDownloadTest {
    @Test fun systemDownloadsPinnedSmallFilesAndReusesLargeModel(): Unit = runBlocking {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        assertTrue("Provision models before the download test", SpeechModelStore.ready(app))
        val model = SpeechModelStore.file(app, "model.int8.onnx")
        val previousMtime = model.lastModified()
        val smallFiles = SpeechModelStore.files.filter { it.name != "model.int8.onnx" }
        val backups = smallFiles.associateWith { SpeechModelStore.file(app, it.name).readBytes() }
        val wifiOnly = SpeechSettings.wifiOnly(app)
        try {
            smallFiles.forEach { check(SpeechModelStore.file(app, it.name).delete()) }
            SpeechSettings.setWifiOnly(app, false)
            SpeechModelStore.download(app)
            withTimeout(180000) {
                while (!SpeechModelStore.ready(app)) {
                    if (SpeechModelStore.state.value.phase == "error") error(SpeechModelStore.state.value.error)
                    delay(250)
                }
            }
            assertEquals("Already verified large weights must not be downloaded again", previousMtime, model.lastModified())
            assertEquals(239233841L, model.length())
            smallFiles.forEach { assertArrayEquals(backups[it], SpeechModelStore.file(app, it.name).readBytes()) }
        } finally {
            SpeechSettings.setWifiOnly(app, wifiOnly)
            for (item in smallFiles) if (!SpeechModelStore.file(app, item.name).isFile) {
                val temporary = File(app.cacheDir, "restore-${item.name}").apply { writeBytes(backups.getValue(item)) }
                try { SpeechModelStore.installFile(app, temporary, item) } finally { temporary.delete() }
            }
        }
    }
}
