// SPDX-License-Identifier: GPL-3.0-or-later
package org.fcitx.fcitx5.android

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Rect
import android.text.InputType
import android.view.accessibility.AccessibilityNodeInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import androidx.core.widget.NestedScrollView
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.fcitx.fcitx5.android.completion.*
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.dataset.InputDataset
import org.fcitx.fcitx5.android.dataset.TelemetrySettings
import org.fcitx.fcitx5.android.input.candidates.floating.FloatingCandidatesMode
import org.fcitx.fcitx5.android.speech.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class SpeechUiTest {
    @Test fun toolbarMicrophoneAndRecordingLifecycle(): Unit = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext
        val automation = instrumentation.uiAutomation
        val prefs = AppPrefs.getInstance()
        val oldCapture = InputDataset.enabled(app)
        val oldPerf = TelemetrySettings.performance(app)
        val oldEnabled = SpeechSettings.enabled(app)
        val oldCompletion = CompletionSettings.enabled(app)
        val oldClipboard = prefs.clipboard.clipboardSuggestion.getValue()
        val oldMode = prefs.candidates.mode.getValue()
        val oldSource = SpeechAudioSources.factory
        InputDataset.setEnabled(app, false); TelemetrySettings.setPerformance(app, false)
        SpeechSettings.setEnabled(app, true); CompletionSettings.setEnabled(app, true)
        prefs.clipboard.clipboardSuggestion.setValue(false); prefs.candidates.mode.setValue(FloatingCandidatesMode.Disabled)
        fun shell(command: String) { automation.executeShellCommand(command).use { fd -> java.io.FileInputStream(fd.fileDescriptor).use { it.readBytes() } } }
        fun nodes(): List<AccessibilityNodeInfo> {
            fun flatten(n: AccessibilityNodeInfo): List<AccessibilityNodeInfo> = listOf(n) + (0 until n.childCount).mapNotNull(n::getChild).flatMap(::flatten)
            return automation.windows.mapNotNull { it.root }.flatMap(::flatten).filter { it.isVisibleToUser }
        }
        fun item(id: String) = nodes().firstOrNull { it.viewIdResourceName == "${app.packageName}:id/$id" }
        fun screenshot(name: String) { File(app.filesDir, name).outputStream().use { checkNotNull(automation.takeScreenshot()).compress(Bitmap.CompressFormat.PNG, 100, it) } }
        suspend fun waitUntil(message: String, condition: () -> Boolean) {
            println("SPEECH_UI_CHECK: $message")
            assertTrue(message, withTimeoutOrNull(120000) { while (!condition()) delay(100); true } == true)
        }
        automation.serviceInfo = automation.serviceInfo.apply {
            flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
        }
        assertTrue(SpeechModelStore.ready(app)); assertTrue(SpeechSettings.hasPermission(app))
        val activity = instrumentation.startActivitySync(Intent(app, SpeechActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        val editor = activity.findViewById<EditText>(R.id.speech_test_input)
        val imm = activity.getSystemService(InputMethodManager::class.java)
        fun focus() = instrumentation.runOnMainSync {
            editor.requestFocus(); imm.restartInput(editor); imm.showSoftInput(editor, InputMethodManager.SHOW_IMPLICIT)
            activity.findViewById<NestedScrollView>(R.id.scroll).scrollTo(0,
                (editor.top - 100 * app.resources.displayMetrics.density).toInt().coerceAtLeast(0))
        }
        try {
            shell("ime set ${app.packageName}/org.fcitx.fcitx5.android.input.FcitxInputMethodService")
            focus()
            waitUntil("microphone is present without installing another voice IME") { item("voice_input_button")?.isClickable == true }
            val bounds = Rect(); item("voice_input_button")!!.getBoundsInScreen(bounds)
            assertTrue("microphone should be on the right edge", bounds.centerX() > app.resources.displayMetrics.widthPixels * 0.9)
            screenshot("speech-toolbar.png")
            QwenCompletionEngine.ensureLoaded(app)
            waitUntil("completion model ready before ASR resource handover") { QwenCompletionEngine.state.value == "ready" }
            assertTrue(item("voice_input_button")!!.performAction(AccessibilityNodeInfo.ACTION_CLICK))
            waitUntil("voice panel opens and the real microphone starts") { item("speech_listening_status")?.text?.toString() == app.getString(R.string.speech_listening) }
            assertEquals("unloaded", QwenCompletionEngine.state.value)
            delay(300) // Accessibility state can precede the corresponding rendered frame.
            screenshot("speech-listening.png")
            assertTrue(item("speech_record_button")!!.performAction(AccessibilityNodeInfo.ACTION_CLICK))
            waitUntil("stop finishes microphone capture") { item("speech_listening_status")?.text?.toString() == app.getString(R.string.speech_no_voice) }
            assertEquals("silence must not insert hallucinated text", "", editor.text.toString())
            val back = nodes().first { it.contentDescription?.toString() == app.getString(R.string.back_to_keyboard) }
            assertTrue(back.performAction(AccessibilityNodeInfo.ACTION_CLICK))
            waitUntil("return restores the keyboard") { item("voice_input_button") != null && item("speech_panel") == null }
            waitUntil("return restores Qwen") { QwenCompletionEngine.state.value == "ready" }
            // Feed a known public recording through the actual IME service, VAD and SenseVoice.
            // Only the audio source changes; production release builds have no injection hook.
            SpeechAudioSources.factory = { SpeechTestAudio.Source(SpeechTestAudio.samples(app)) }
            assertTrue(item("voice_input_button")!!.performAction(AccessibilityNodeInfo.ACTION_CLICK))
            waitUntil("recognized Chinese is committed into the real focused editor") {
                editor.text.toString().contains("时间早上9点至下午5点")
            }
            assertEquals("The editor receives exactly the recognized text", item("speech_transcript")?.text?.toString(), editor.text.toString())
            delay(200)
            screenshot("speech-chinese-committed.png")
            assertTrue(nodes().first { it.contentDescription?.toString() == app.getString(R.string.back_to_keyboard) }
                .performAction(AccessibilityNodeInfo.ACTION_CLICK))
            waitUntil("keyboard returns after fixture recognition") { item("voice_input_button") != null }
            SpeechAudioSources.factory = oldSource
            instrumentation.runOnMainSync { editor.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD }
            focus(); delay(1000)
            assertNull("password fields must not expose microphone", item("voice_input_button"))
            instrumentation.runOnMainSync { editor.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE }
            SpeechSettings.setEnabled(app, false); focus(); delay(1000)
            assertNull("the setting hides microphone", item("voice_input_button"))
            SpeechSettings.setEnabled(app, true); focus()
            waitUntil("microphone setting can be reenabled") { item("voice_input_button") != null }
            instrumentation.runOnMainSync {
                imm.hideSoftInputFromWindow(editor.windowToken, 0)
                editor.clearFocus()
                activity.findViewById<NestedScrollView>(R.id.scroll).scrollTo(0, 0)
            }
            delay(500); screenshot("speech-settings.png")
        } catch (error: Throwable) { screenshot("speech-ui-failure.png"); throw error }
        finally {
            SpeechAudioSources.factory = oldSource
            InputDataset.setEnabled(app, oldCapture); TelemetrySettings.setPerformance(app, oldPerf)
            SpeechSettings.setEnabled(app, oldEnabled); CompletionSettings.setEnabled(app, oldCompletion)
            prefs.clipboard.clipboardSuggestion.setValue(oldClipboard); prefs.candidates.mode.setValue(oldMode)
            instrumentation.runOnMainSync { activity.finish() }
        }
    }
}
