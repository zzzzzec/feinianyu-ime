// SPDX-License-Identifier: GPL-3.0-or-later
package org.fcitx.fcitx5.android

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.view.View
import android.view.ViewGroup
import android.view.inspector.WindowInspector
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.ImageView
import androidx.core.view.ViewCompat
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.fcitx.fcitx5.android.completion.*
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.theme.*
import org.fcitx.fcitx5.android.dataset.InputDataset
import org.fcitx.fcitx5.android.dataset.TelemetrySettings
import org.fcitx.fcitx5.android.input.InputView
import org.fcitx.fcitx5.android.input.candidates.floating.FloatingCandidatesMode
import org.junit.Assert.*
import org.junit.Test
import java.io.File

@SdkSuppress(minSdkVersion = 29)
class CompletionMarkerUiTest {
    @Test fun qwenOriginIsVisibleAndSameWordsLoseTheirMarkWhenSourceChanges(): Unit = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext
        val automation = instrumentation.uiAutomation
        val prefs = AppPrefs.getInstance()
        val oldEnabled = CompletionSettings.enabled(app)
        val oldCapture = InputDataset.enabled(app)
        val oldPerf = TelemetrySettings.performance(app)
        val oldClipboard = prefs.clipboard.clipboardSuggestion.getValue()
        val oldMode = prefs.candidates.mode.getValue()
        val oldTheme = ThemeManager.prefs.normalModeTheme.getValue()
        val oldFollow = ThemeManager.prefs.followSystemDayNightTheme.getValue()
        fun shell(command: String) { automation.executeShellCommand(command).use { fd -> java.io.FileInputStream(fd.fileDescriptor).use { it.readBytes() } } }
        fun flatten(view: View): List<View> = listOf(view) + if (view is ViewGroup)
            (0 until view.childCount).flatMap { flatten(view.getChildAt(it)) } else emptyList()
        fun views() = WindowInspector.getGlobalWindowViews().flatMap(::flatten)
        fun visible(view: View) = view.isShown && view.getGlobalVisibleRect(Rect())
        fun markers() = views().filter { it.id == R.id.completion_qwen_marker && visible(it) }
        fun candidates() = views().filter { it.id == R.id.completion_suggestion && visible(it) }
        suspend fun waitUntil(message: String, condition: () -> Boolean) {
            var satisfied = false
            val passed = withTimeoutOrNull(15000) {
                while (!satisfied) {
                    instrumentation.runOnMainSync { satisfied = condition() }
                    if (!satisfied) delay(50)
                }
                true
            } == true
            assertTrue(message, passed)
        }
        fun screenshot(name: String) { File(app.filesDir, name).outputStream().use {
            checkNotNull(automation.takeScreenshot()).compress(Bitmap.CompressFormat.PNG, 100, it)
        }
            instrumentation.runOnMainSync {
                views().filterIsInstance<InputView>().firstOrNull { it.isShown }?.keyboardView?.let { keyboard ->
                    val image = Bitmap.createBitmap(keyboard.width, keyboard.height, Bitmap.Config.ARGB_8888)
                    keyboard.draw(Canvas(image))
                    File(app.filesDir, name.removeSuffix(".png") + "-keyboard.png").outputStream().use {
                        image.compress(Bitmap.CompressFormat.PNG, 100, it)
                    }
                    image.recycle()
                }
            }
        }
        InputDataset.setEnabled(app, false); TelemetrySettings.setPerformance(app, false)
        CompletionSettings.setEnabled(app, false)
        prefs.clipboard.clipboardSuggestion.setValue(false)
        prefs.candidates.mode.setValue(FloatingCandidatesMode.Disabled)
        ThemeManager.prefs.followSystemDayNightTheme.setValue(false)
        val activity = instrumentation.startActivitySync(Intent(app, CompletionActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        val editor = activity.findViewById<EditText>(R.id.completion_test_input)
        val imm = activity.getSystemService(InputMethodManager::class.java)
        val words = listOf("一起商量一下", "明天下午再讨论", "等你有空的时候再告诉我")
        try {
            shell("ime set ${app.packageName}/org.fcitx.fcitx5.android.input.FcitxInputMethodService")
            for (theme in listOf(ThemePreset.GboardStage1Light, ThemePreset.MaterialDark)) {
                instrumentation.runOnMainSync {
                    ThemeManager.setNormalModeTheme(theme)
                    editor.requestFocus(); editor.setText("我们可以")
                    editor.setSelection(editor.length()); imm.restartInput(editor)
                    imm.showSoftInput(editor, InputMethodManager.SHOW_IMPLICIT)
                }
                waitUntil("The real candidate bar should be ready") { views().filterIsInstance<InputView>().any { it.completionAvailable } }
                delay(500)
                var input: InputView? = null
                instrumentation.runOnMainSync {
                    input = views().filterIsInstance<InputView>().first { it.completionAvailable }
                    assertTrue(input!!.showCompletion(words, "qwen") { true })
                }
                waitUntil("Qwen candidates should have a subtle source marker") { markers().isNotEmpty() && candidates().all {
                    ViewCompat.getStateDescription(it) == app.getString(R.string.completion_source_qwen)
                } }
                instrumentation.runOnMainSync {
                    markers().forEach { marker ->
                        assertEquals(theme.candidateTextColor, (marker as ImageView).imageTintList!!.defaultColor)
                        assertEquals(0.55f, marker.alpha, 0.001f)
                    }
                }
                delay(150); screenshot("completion-marker-${theme.name}.png")
                instrumentation.runOnMainSync { views().first { it.id == R.id.expand_candidate_btn && visible(it) }.performClick() }
                waitUntil("Expanded Qwen candidates should keep their marker") { candidates().size >= 3 && markers().size == candidates().size }
                delay(150); screenshot("completion-marker-expanded-${theme.name}.png")
                // Keep the exact same candidate objects and stable IDs; only their origin changes.
                instrumentation.runOnMainSync { assertTrue(input!!.showCompletion(words, "dictionary") { true }) }
                waitUntil("Dictionary continuations must lose Qwen's recycled decoration") { candidates().isNotEmpty() && markers().isEmpty() && candidates().all { ViewCompat.getStateDescription(it) == null } }
                delay(100); screenshot("completion-marker-dictionary-${theme.name}.png")
                instrumentation.runOnMainSync { input!!.showCompletion(null, null) }
                waitUntil("Clearing completion must remove all markers") { markers().isEmpty() }
                assertEquals("Showing a source marker must not change editor text", "我们可以", editor.text.toString())
            }
        } catch (error: Throwable) { screenshot("completion-marker-failure.png"); throw error }
        finally {
            CompletionSettings.setEnabled(app, oldEnabled)
            prefs.clipboard.clipboardSuggestion.setValue(oldClipboard); prefs.candidates.mode.setValue(oldMode)
            instrumentation.runOnMainSync {
                ThemeManager.setNormalModeTheme(oldTheme)
                ThemeManager.prefs.followSystemDayNightTheme.setValue(oldFollow)
                activity.finish()
            }
            InputDataset.setEnabled(app, oldCapture); TelemetrySettings.setPerformance(app, oldPerf)
        }
    }
}
