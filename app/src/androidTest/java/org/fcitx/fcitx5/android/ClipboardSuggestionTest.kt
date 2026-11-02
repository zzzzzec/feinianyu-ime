// SPDX-License-Identifier: GPL-3.0-or-later
package org.fcitx.fcitx5.android

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ClipData
import android.content.Intent
import android.graphics.Bitmap
import android.os.PersistableBundle
import android.view.accessibility.AccessibilityNodeInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import androidx.core.widget.NestedScrollView
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.fcitx.fcitx5.android.completion.CompletionActivity
import org.fcitx.fcitx5.android.completion.CompletionSettings
import org.fcitx.fcitx5.android.data.clipboard.ClipboardManager
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.fixture.ImageFixture
import org.fcitx.fcitx5.android.dataset.InputDataset
import org.fcitx.fcitx5.android.dataset.TelemetrySettings
import org.fcitx.fcitx5.android.input.candidates.floating.FloatingCandidatesMode
import org.fcitx.fcitx5.android.utils.appContext
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class ClipboardSuggestionTest {
    @Test fun completeTextAndImageSuggestions(): Unit = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext
        val automation = instrumentation.uiAutomation
        val prefs = AppPrefs.getInstance()
        val clipPrefs = prefs.clipboard
        val system = appContext.getSystemService(android.content.ClipboardManager::class.java)
        val oldListening = clipPrefs.clipboardListening.getValue()
        val oldSuggestion = clipPrefs.clipboardSuggestion.getValue()
        val oldTimeout = clipPrefs.clipboardItemTimeout.getValue()
        val oldMask = clipPrefs.clipboardMaskSensitive.getValue()
        val oldUsed = prefs.internal.usedClipboardSuggestion.getValue()
        val oldCompletion = CompletionSettings.enabled(app)
        val oldMode = prefs.candidates.mode.getValue()
        val oldDictionary = CompletionSettings.dictionaryEnabled(app)
        val oldCapture = InputDataset.enabled(app)
        val oldPerformance = TelemetrySettings.performance(app)
        var listenerRemoved = false
        fun shell(command: String) { automation.executeShellCommand(command).use { fd -> java.io.FileInputStream(fd.fileDescriptor).use { it.readBytes() } } }
        fun allNodes(): List<AccessibilityNodeInfo> {
            fun flatten(node: AccessibilityNodeInfo): List<AccessibilityNodeInfo> = listOf(node) + (0 until node.childCount).mapNotNull { node.getChild(it) }.flatMap(::flatten)
            return automation.windows.mapNotNull { it.root }.flatMap(::flatten).filter { it.isVisibleToUser }
        }
        fun chip() = allNodes().firstOrNull { it.viewIdResourceName == "${app.packageName}:id/clipboard_suggestion_chip" }
        suspend fun waitUntil(message: String, condition: () -> Boolean) {
            println("CLIPBOARD_CHECK: $message")
            val completed = withTimeoutOrNull(10000) { while (!condition()) delay(40); true } == true
            if (!completed) File(app.filesDir, "clipboard-suggestion-failure.png").outputStream().use {
                checkNotNull(automation.takeScreenshot()).compress(Bitmap.CompressFormat.PNG, 100, it)
            }
            assertTrue(message, completed)
        }
        fun screenshot(name: String) { File(app.filesDir, name).outputStream().use { checkNotNull(automation.takeScreenshot()).compress(Bitmap.CompressFormat.PNG, 100, it) } }
        clipPrefs.clipboardListening.setValue(true); clipPrefs.clipboardSuggestion.setValue(true)
        InputDataset.setEnabled(app, false); TelemetrySettings.setPerformance(app, false)
        clipPrefs.clipboardItemTimeout.setValue(120); clipPrefs.clipboardMaskSensitive.setValue(true)
        prefs.candidates.mode.setValue(FloatingCandidatesMode.Disabled)
        CompletionSettings.setEnabled(app, true)
        CompletionSettings.setDictionaryEnabled(app, true)
        automation.serviceInfo = automation.serviceInfo.apply {
            flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
        }
        val activity = instrumentation.startActivitySync(Intent(app, CompletionActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        val editor = activity.findViewById<EditText>(R.id.completion_test_input)
        val imm = activity.getSystemService(InputMethodManager::class.java)
        fun focus(value: String) = instrumentation.runOnMainSync {
            editor.requestFocus(); editor.setText(value); editor.setSelection(value.length)
            imm.restartInput(editor); imm.showSoftInput(editor, InputMethodManager.SHOW_IMPLICIT)
            activity.findViewById<NestedScrollView>(R.id.scroll).scrollTo(0, (editor.top - 100 * app.resources.displayMetrics.density).toInt().coerceAtLeast(0))
        }
        try {
            shell("ime set ${app.packageName}/org.fcitx.fcitx5.android.input.FcitxInputMethodService")
            focus(""); delay(500)
            // Miss a real copy callback, then enter the editor: the start-input refresh must recover it.
            instrumentation.runOnMainSync { system.removePrimaryClipChangedListener(ClipboardManager) }
            listenerRemoved = true
            val payload = "整段原文😀\n网址 https://example.test/a?x=1 电话 12345678901\n最后一行，保留标点。"
            instrumentation.runOnMainSync { system.setPrimaryClip(ClipData.newPlainText("synthetic", payload)) }
            delay(150); assertNotEquals(payload, ClipboardManager.lastEntry?.text)
            val prefix = "春眠不觉晓，"
            focus(prefix)
            waitUntil("Copy missed by the listener must be recovered when entering an editor") { chip() != null && ClipboardManager.lastEntry?.text == payload }
            delay(1000)
            assertNotNull("Dictionary/Qwen must not cover the clipboard suggestion", chip())
            assertTrue(allNodes().none { it.viewIdResourceName == "${app.packageName}:id/completion_suggestion" })
            screenshot("clipboard-suggestion-text.png")
            assertTrue(chip()!!.performAction(AccessibilityNodeInfo.ACTION_CLICK))
            waitUntil("The complete unsegmented original must be pasted") { editor.text.toString() == prefix + payload }
            waitUntil("A successful paste consumes the suggestion") { chip() == null }
            focus(""); delay(250); assertNull("The same copy must not reappear in another editor", chip())
            instrumentation.runOnMainSync { system.addPrimaryClipChangedListener(ClipboardManager) }; listenerRemoved = false
            delay(20)
            instrumentation.runOnMainSync { system.setPrimaryClip(ClipData.newPlainText("synthetic", payload)) }
            waitUntil("Copying the same text again is a new suggestion") { chip() != null }
            clipPrefs.clipboardItemTimeout.setValue(1)
            waitUntil("Expiry must hide the widget without changing focus") { chip() == null }
            clipPrefs.clipboardItemTimeout.setValue(-1)
            waitUntil("Unlimited lifetime must also work after the original timeout") { chip() != null }
            shell("input text ni")
            waitUntil("Composing pinyin must take priority over the clipboard") { chip() == null }
            focus("")
            val secret = "synthetic-sensitive-clipboard"
            instrumentation.runOnMainSync {
                val clip = ClipData.newPlainText("synthetic", secret)
                clip.description.extras = PersistableBundle().apply { putBoolean("android.content.extra.IS_SENSITIVE", true) }
                system.setPrimaryClip(clip)
            }
            waitUntil("A masked sensitive suggestion should remain usable") { chip() != null && ClipboardManager.lastEntry?.text == secret }
            assertTrue("Sensitive clipboard text must not appear in the preview", allNodes().none { it.text?.toString()?.contains(secret) == true })
            assertTrue(chip()!!.performAction(AccessibilityNodeInfo.ACTION_CLICK))
            waitUntil("Masking must not change the pasted payload") { editor.text.toString() == secret }
            shell("am start -n ${app.packageName}.test/org.fcitx.fcitx5.android.fixture.ImageClipboardActivity")
            waitUntil("Image fixture should be visible") { allNodes().any { it.text?.toString().equals("Copy test image", ignoreCase = true) } }
            assertTrue(allNodes().first { it.text?.toString().equals("Copy test image", ignoreCase = true) }.performAction(AccessibilityNodeInfo.ACTION_CLICK))
            waitUntil("A copied image should appear as a quick-paste suggestion") { chip() != null && ClipboardManager.lastEntry?.isImage == true }
            assertEquals(app.getString(R.string.clipboard_paste_image), chip()!!.contentDescription.toString())
            screenshot("clipboard-suggestion-image.png")
            assertTrue(chip()!!.performAction(AccessibilityNodeInfo.ACTION_CLICK))
            val expectedHash = ImageFixture.hash(ImageFixture.png(64, 96))
            waitUntil("The receiving app must read the original image bytes") { allNodes().any { it.text?.toString()?.let { text -> text.startsWith("IMAGE_RECEIVED image/png") && text.contains(expectedHash) } == true } }
            waitUntil("A successful image insertion consumes the suggestion") { chip() == null }
            screenshot("clipboard-suggestion-image-pasted.png")
        } finally {
            if (listenerRemoved) instrumentation.runOnMainSync { system.addPrimaryClipChangedListener(ClipboardManager) }
            clipPrefs.clipboardListening.setValue(oldListening); clipPrefs.clipboardSuggestion.setValue(oldSuggestion)
            clipPrefs.clipboardItemTimeout.setValue(oldTimeout); clipPrefs.clipboardMaskSensitive.setValue(oldMask)
            prefs.internal.usedClipboardSuggestion.setValue(oldUsed)
            prefs.candidates.mode.setValue(oldMode)
            CompletionSettings.setEnabled(app, oldCompletion)
            CompletionSettings.setDictionaryEnabled(app, oldDictionary)
            InputDataset.setEnabled(app, oldCapture); TelemetrySettings.setPerformance(app, oldPerformance)
            instrumentation.runOnMainSync { activity.finish() }
        }
    }
}
