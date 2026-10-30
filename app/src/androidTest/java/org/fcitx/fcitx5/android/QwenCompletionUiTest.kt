// SPDX-License-Identifier: GPL-3.0-or-later
package org.fcitx.fcitx5.android

import android.content.Intent
import android.graphics.Bitmap
import android.accessibilityservice.AccessibilityServiceInfo
import android.os.SystemClock
import android.text.InputType
import android.view.accessibility.AccessibilityNodeInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.fcitx.fcitx5.android.completion.*
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.input.candidates.floating.FloatingCandidatesMode
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** Start with another IME selected; this test selects our IME after instrumentation starts. */
class QwenCompletionUiTest {
    @Test fun renderedCandidateCommitsAndStaleCandidateCannotCommit(): Unit = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext
        val oldBudget = CompletionSettings.budgetMs(app)
        val oldEnabled = CompletionSettings.enabled(app)
        CompletionSettings.setEnabled(app, true)
        val floatingMode = AppPrefs.getInstance().candidates.mode
        val oldMode = floatingMode.getValue()
        floatingMode.setValue(FloatingCandidatesMode.Disabled)
        CompletionSettings.setBudgetMs(app, 10000) // Emulator functional testing, not the production latency target.
        QwenCompletionEngine.ensureLoaded(app)
        withTimeout(60000) { while (QwenCompletionEngine.state.value != "ready") delay(100) }
        val automation = instrumentation.uiAutomation
        automation.executeShellCommand("ime set ${app.packageName}/org.fcitx.fcitx5.android.input.FcitxInputMethodService").close()
        automation.serviceInfo = automation.serviceInfo.apply {
            flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
        }
        val activity = instrumentation.startActivitySync(Intent(app, CompletionActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        val editor = activity.findViewById<EditText>(R.id.completion_test_input)
        fun candidate(): AccessibilityNodeInfo? = automation.windows.asSequence().mapNotNull { it.root }
            .flatMap { it.findAccessibilityNodeInfosByViewId("${app.packageName}:id/completion_suggestion").asSequence() }
            .firstOrNull { it.isVisibleToUser && !it.text.isNullOrBlank() }
        suspend fun waitForCandidate(): AccessibilityNodeInfo {
            withTimeout(15000) { while (candidate() == null) delay(50) }
            return checkNotNull(candidate())
        }
        fun type(prefix: String) = instrumentation.runOnMainSync {
            editor.requestFocus(); editor.setText(prefix); editor.setSelection(prefix.length)
            activity.getSystemService(InputMethodManager::class.java).showSoftInput(editor, InputMethodManager.SHOW_IMPLICIT)
        }
        try {
            delay(1000)
            val prefix = "今天辛苦了，早点"
            type(prefix)
            val first = waitForCandidate()
            val text = first.text.toString()
            instrumentation.runOnMainSync {
                val toolbar = activity.findViewById<android.view.View>(R.id.toolbar)
                val location = IntArray(2).also(toolbar::getLocationOnScreen)
                val statusBar = androidx.core.view.ViewCompat.getRootWindowInsets(toolbar)!!
                    .getInsetsIgnoringVisibility(androidx.core.view.WindowInsetsCompat.Type.statusBars()).top
                assertTrue("Toolbar overlaps the status bar: top=${location[1]}, inset=$statusBar", location[1] >= statusBar)
            }
            File(app.filesDir, "qwen-ui-candidate.png").outputStream().use {
                checkNotNull(automation.takeScreenshot()).compress(Bitmap.CompressFormat.PNG, 100, it)
            }
            assertTrue(first.performAction(AccessibilityNodeInfo.ACTION_CLICK))
            delay(200)
            instrumentation.runOnMainSync { assertEquals(prefix + text, editor.text.toString()) }
            File(app.filesDir, "qwen-ui-accepted.png").outputStream().use {
                checkNotNull(automation.takeScreenshot()).compress(Bitmap.CompressFormat.PNG, 100, it)
            }
            type("这份文件我看过了，有几个地方需要")
            val stale = waitForCandidate()
            instrumentation.runOnMainSync { editor.setSelection(0) }
            delay(250)
            stale.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            delay(100)
            instrumentation.runOnMainSync { assertEquals("这份文件我看过了，有几个地方需要", editor.text.toString()) }
            assertNull(candidate())
            type("今天辛苦了，早点")
            val disabled = waitForCandidate()
            CompletionSettings.setEnabled(app, false)
            delay(250)
            assertNull("Switching off hides the existing suggestion", candidate())
            disabled.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            delay(100)
            instrumentation.runOnMainSync { assertEquals("今天辛苦了，早点", editor.text.toString()) }
            CompletionSettings.setEnabled(app, true)
            instrumentation.runOnMainSync {
                editor.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                activity.getSystemService(InputMethodManager::class.java).restartInput(editor)
            }
            type("只用于测试的密码文本")
            delay(1000)
            assertNull("No completion in password fields", candidate())
            File(app.filesDir, "qwen-ui-perf-export.json").writeText(CompletionPerf.export(app))
        } finally {
            CompletionSettings.setBudgetMs(app, oldBudget)
            CompletionSettings.setEnabled(app, oldEnabled)
            floatingMode.setValue(oldMode)
            instrumentation.runOnMainSync { activity.finish() }
        }
    }
}
