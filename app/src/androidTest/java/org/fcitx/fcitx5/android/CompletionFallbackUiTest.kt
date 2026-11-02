// SPDX-License-Identifier: GPL-3.0-or-later
package org.fcitx.fcitx5.android

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.graphics.Bitmap
import android.view.accessibility.AccessibilityNodeInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import androidx.core.widget.NestedScrollView
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.fcitx.fcitx5.android.completion.*
import org.fcitx.fcitx5.android.core.*
import org.fcitx.fcitx5.android.daemon.FcitxDaemon
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.dataset.InputDataset
import org.fcitx.fcitx5.android.dataset.TelemetrySettings
import org.fcitx.fcitx5.android.input.candidates.floating.FloatingCandidatesMode
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** Start with another IME selected. Uses the real native engine and dictionary/UI paths. */
class CompletionFallbackUiTest {
    @Test fun nativeFallbackAndAiFirstMixedCandidatesCommitCorrectSources(): Unit = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext
        val automation = instrumentation.uiAutomation
        val prefs = AppPrefs.getInstance()
        val oldEnabled = CompletionSettings.enabled(app)
        val oldDictionary = CompletionSettings.dictionaryEnabled(app)
        val oldBudget = CompletionSettings.budgetMs(app)
        val oldMode = prefs.candidates.mode.getValue()
        val oldClipboard = prefs.clipboard.clipboardSuggestion.getValue()
        val oldCapture = InputDataset.enabled(app)
        val oldTelemetryPerf = TelemetrySettings.performance(app)
        fun shell(command: String) { automation.executeShellCommand(command).use { fd -> java.io.FileInputStream(fd.fileDescriptor).use { it.readBytes() } } }
        fun nodes(): List<AccessibilityNodeInfo> {
            fun flatten(n: AccessibilityNodeInfo): List<AccessibilityNodeInfo> = listOf(n) + (0 until n.childCount).mapNotNull(n::getChild).flatMap(::flatten)
            return automation.windows.mapNotNull { it.root }.flatMap(::flatten).filter { it.isVisibleToUser }
        }
        fun items(id: String) = nodes().filter { it.viewIdResourceName == "${app.packageName}:id/$id" && it.isClickable }
        fun screenshot(name: String) { File(app.filesDir, name).outputStream().use { checkNotNull(automation.takeScreenshot()).compress(Bitmap.CompressFormat.PNG, 100, it) } }
        suspend fun waitUntil(message: String, condition: () -> Boolean) {
            println("FALLBACK_CHECK: $message")
            val ok = withTimeoutOrNull(15000) { while (!condition()) delay(50); true } == true
            if (!ok) screenshot("completion-fallback-failure.png")
            assertTrue(message, ok)
        }
        InputDataset.setEnabled(app, false); TelemetrySettings.setPerformance(app, false)
        prefs.clipboard.clipboardSuggestion.setValue(false)
        prefs.candidates.mode.setValue(FloatingCandidatesMode.Disabled)
        CompletionSettings.setEnabled(app, false)
        CompletionSettings.setBudgetMs(app, 10000)
        automation.serviceInfo = automation.serviceInfo.apply {
            flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
        }
        val activity = instrumentation.startActivitySync(Intent(app, CompletionActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        val editor = activity.findViewById<EditText>(R.id.completion_test_input)
        val imm = activity.getSystemService(InputMethodManager::class.java)
        val clientName = "completion-fallback-ui-test"
        val fcitx = FcitxDaemon.connect(clientName)
        var nativeModelUnloaded = false
        var oldIme: String? = null
        suspend fun compose(pinyin: String, text: String) {
            CompletionSettings.setEnabled(app, false)
            instrumentation.runOnMainSync {
                editor.requestFocus(); editor.setText(""); imm.restartInput(editor)
                imm.showSoftInput(editor, InputMethodManager.SHOW_IMPLICIT)
                activity.findViewById<NestedScrollView>(R.id.scroll).scrollTo(0,
                    (editor.top - 100 * app.resources.displayMetrics.density).toInt().coerceAtLeast(0))
            }
            fcitx.runOnReady { reset(); activateIme("pinyin") }
            delay(250)
            // Drive setup keys synchronously; Android's injected key events may
            // still be queued when a prefix already offers the expected phrase.
            fcitx.runOnReady { for (key in pinyin) sendKey(key) }
            var selected = -1
            waitUntil("Pinyin composition should offer $text") {
                selected = runBlocking { fcitx.runOnReady { getCandidates(0, 16) } }.indexOfFirst { it.text == text }
                selected >= 0
            }
            assertTrue(fcitx.runOnReady { select(selected) })
            waitUntil("Pinyin should commit the intended text") { editor.text.toString() == text }
            waitUntil("Native post-commit predictions should be visible with AI disabled") { items("native_prediction_suggestion").isNotEmpty() }
        }
        try {
            shell("ime set ${app.packageName}/org.fcitx.fcitx5.android.input.FcitxInputMethodService")
            oldIme = fcitx.runOnReady { inputMethodEntryCached.uniqueName }
            compose("nihao", "你好")
            assertTrue(items("completion_suggestion").isEmpty())
            screenshot("completion-native-fallback.png")

            // A real native-runtime failure must leave native predictions visible.
            QwenCompletionEngine.setActive(app, true)
            withTimeout(120000) { while (QwenCompletionEngine.state.value != "ready") delay(100) }
            QwenNative.unload(); nativeModelUnloaded = true
            CompletionSettings.setDictionaryEnabled(app, false)
            val failureStart = System.currentTimeMillis()
            CompletionSettings.setEnabled(app, true)
            waitUntil("A failed AI inference should be recorded") {
                val records = runBlocking { JSONObject(CompletionPerf.export(app)).getJSONArray("records") }
                (0 until records.length()).any { i -> records.getJSONObject(i).let {
                    it.optString("phase") == "ui" && it.optString("state") == "not_ready" && it.optLong("wall_time_ms") >= failureStart
                } }
            }
            assertTrue("AI failure must keep native predictions", items("native_prediction_suggestion").isNotEmpty())
            assertTrue(items("completion_suggestion").isEmpty())
            CompletionSettings.setEnabled(app, false)
            QwenNative.load(checkNotNull(QwenModelStore.readyFile(app)).absolutePath); nativeModelUnloaded = false

            val prefix = "春眠不觉晓"
            compose("chunmianbujuexiao", prefix)
            val originals = fcitx.runOnReady { getCandidates(0, 100) }
            assertTrue(originals.isNotEmpty())
            CompletionSettings.setDictionaryEnabled(app, true)
            CompletionSettings.setEnabled(app, true)
            waitUntil("Dictionary/AI suggestions should join the native candidates") { items("completion_suggestion").isNotEmpty() }
            val horizontal = nodes().first { it.viewIdResourceName == "${app.packageName}:id/candidate_view" }
            assertEquals("AI suggestions belong at the front", "${app.packageName}:id/completion_suggestion", horizontal.getChild(0).viewIdResourceName)
            screenshot("completion-ai-first-mixed.png")
            val expand = nodes().firstOrNull { it.viewIdResourceName == "${app.packageName}:id/expand_candidate_btn" && it.isClickable }
            expand?.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            waitUntil("Original predictions must remain selectable after the AI prefix") { items("native_prediction_suggestion").isNotEmpty() }
            val native = items("native_prediction_suggestion").first()
            val nativeText = native.contentDescription.toString()
            assertTrue("Merged native items come from the engine", originals.any { it.text == nativeText })
            assertTrue(native.performAction(AccessibilityNodeInfo.ACTION_CLICK))
            waitUntil("Merged display position must select the original native index") { editor.text.toString() == prefix + nativeText }
            screenshot("completion-mixed-native-accepted.png")

            compose("chunmianbujuexiao", prefix)
            CompletionSettings.setEnabled(app, true)
            waitUntil("AI should be offered again for the new context") { items("completion_suggestion").isNotEmpty() }
            val ai = items("completion_suggestion").first()
            val aiText = ai.contentDescription.toString()
            assertTrue(ai.performAction(AccessibilityNodeInfo.ACTION_CLICK))
            waitUntil("AI selection commits its complete text instead of a native index") { editor.text.toString() == prefix + aiText }
            screenshot("completion-ai-accepted-with-fallback.png")
            CompletionSettings.setEnabled(app, false)
            compose("nihao", "你好")
            val fallback = items("native_prediction_suggestion").first()
            val fallbackText = fallback.contentDescription.toString()
            assertTrue(fallback.performAction(AccessibilityNodeInfo.ACTION_CLICK))
            waitUntil("Native fallback remains functional when AI is switched off") { editor.text.toString() == "你好" + fallbackText }
        } catch (error: Throwable) {
            screenshot("completion-fallback-failure.png")
            throw error
        } finally {
            if (nativeModelUnloaded) QwenModelStore.readyFile(app)?.let { QwenNative.load(it.absolutePath) }
            oldIme?.let { fcitx.runOnReady { activateIme(it) } }
            CompletionSettings.setEnabled(app, oldEnabled); CompletionSettings.setDictionaryEnabled(app, oldDictionary)
            CompletionSettings.setBudgetMs(app, oldBudget)
            prefs.clipboard.clipboardSuggestion.setValue(oldClipboard); prefs.candidates.mode.setValue(oldMode)
            InputDataset.setEnabled(app, oldCapture); TelemetrySettings.setPerformance(app, oldTelemetryPerf)
            instrumentation.runOnMainSync { activity.finish() }
            FcitxDaemon.disconnect(clientName)
        }
    }
}
