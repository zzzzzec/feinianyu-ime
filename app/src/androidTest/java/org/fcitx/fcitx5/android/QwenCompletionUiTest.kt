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
import androidx.appcompat.widget.AppCompatSpinner
import androidx.core.widget.NestedScrollView
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.fcitx.fcitx5.android.completion.*
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.input.candidates.floating.FloatingCandidatesMode
import org.junit.Assert.*
import org.junit.Test
import org.json.JSONObject
import java.io.File

/** Start with another IME selected; this test selects our IME after instrumentation starts. */
class QwenCompletionUiTest {
    @Test fun renderedCandidateCommitsAndStaleCandidateCannotCommit(): Unit = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext
        val oldBudget = CompletionSettings.budgetMs(app)
        val oldEnabled = CompletionSettings.enabled(app)
        val oldPolicy = CompletionSettings.policy(app)
        CompletionSettings.setEnabled(app, true)
        CompletionSettings.setPolicy(app, CompletionConfidence.policies.last())
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
        fun candidates(): List<AccessibilityNodeInfo> = automation.windows.asSequence().mapNotNull { it.root }
            .flatMap { it.findAccessibilityNodeInfosByViewId("${app.packageName}:id/completion_suggestion").asSequence() }
            .filter { it.isVisibleToUser && !it.contentDescription.isNullOrBlank() }.toList()
        fun candidate() = candidates().firstOrNull()
        fun candidateText(node: AccessibilityNodeInfo) = node.contentDescription.toString()
        fun expandButton(): AccessibilityNodeInfo? = automation.windows.asSequence().mapNotNull { it.root }
            .flatMap { it.findAccessibilityNodeInfosByViewId("${app.packageName}:id/expand_candidate_btn").asSequence() }
            .firstOrNull { it.isVisibleToUser && it.isEnabled && it.isClickable }
        suspend fun waitForCandidate(): AccessibilityNodeInfo {
            withTimeout(15000) { while (candidate() == null) delay(50) }
            return checkNotNull(candidate())
        }
        fun type(prefix: String) = instrumentation.runOnMainSync {
            editor.requestFocus(); editor.setText(prefix); editor.setSelection(prefix.length)
            activity.getSystemService(InputMethodManager::class.java).showSoftInput(editor, InputMethodManager.SHOW_IMPLICIT)
            activity.findViewById<NestedScrollView>(R.id.scroll).scrollTo(0,
                (editor.top - 100 * app.resources.displayMetrics.density).toInt().coerceAtLeast(0))
        }
        fun conservative() = instrumentation.runOnMainSync {
            activity.findViewById<AppCompatSpinner>(R.id.completion_confidence_selector).setSelection(0)
            CompletionSettings.setPolicy(app, CompletionConfidence.policies.first())
        }
        try {
            delay(1000)
            val prefix = "这份文件我看过了，有几个地方需要"
            type(prefix)
            waitForCandidate()
            assertTrue("Completion items belong to the original horizontal candidate RecyclerView", candidates().all { node ->
                generateSequence(node) { it.parent }.any { it.viewIdResourceName == "${app.packageName}:id/candidate_view" }
            })
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
            val seen = candidates().map(::candidateText).toMutableSet()
            val expand = expandButton()
            if (expand != null) {
                assertTrue(expand.performAction(AccessibilityNodeInfo.ACTION_CLICK))
                withTimeout(5000) { while (candidates().none { node -> generateSequence(node) { it.parent }
                    .any { it.viewIdResourceName == "${app.packageName}:id/expanded_candidate_view" } }) delay(50) }
                seen.addAll(candidates().map(::candidateText))
            }
            assertTrue("Only eligible choices are shown, with no fixed minimum count", seen.size in 1..3)
            val selected = candidates().getOrElse(1) { candidates().last() }
            val text = candidateText(selected)
            assertTrue(selected.performAction(AccessibilityNodeInfo.ACTION_CLICK))
            delay(200)
            instrumentation.runOnMainSync { assertEquals(prefix + text, editor.text.toString()) }
            File(app.filesDir, "qwen-ui-accepted.png").outputStream().use {
                checkNotNull(automation.takeScreenshot()).compress(Bitmap.CompressFormat.PNG, 100, it)
            }
            conservative()
            val strong = "春眠不觉晓，"
            type(strong)
            val single = waitForCandidate()
            assertEquals(1, candidates().size)
            assertEquals("处处闻啼鸟。", candidateText(single))
            File(app.filesDir, "qwen-ui-confidence-single.png").outputStream().use {
                checkNotNull(automation.takeScreenshot()).compress(Bitmap.CompressFormat.PNG, 100, it)
            }
            assertTrue(single.performAction(AccessibilityNodeInfo.ACTION_CLICK))
            delay(200)
            instrumentation.runOnMainSync { assertEquals(strong + "处处闻啼鸟。", editor.text.toString()) }
            val abstainStart = System.currentTimeMillis()
            type("明月松间照，")
            withTimeout(10000) {
                while (true) {
                    val records = JSONObject(CompletionPerf.export(app)).getJSONArray("records")
                    if ((0 until records.length()).any { index -> records.getJSONObject(index).let {
                        it.optString("phase") == "ui" && it.optString("state") == "abstained" && it.optLong("wall_time_ms") >= abstainStart
                    } }) break
                    delay(100)
                }
            }
            assertNull("Low confidence must really finish without any visible suggestion", candidate())
            File(app.filesDir, "qwen-ui-confidence-abstain.png").outputStream().use {
                checkNotNull(automation.takeScreenshot()).compress(Bitmap.CompressFormat.PNG, 100, it)
            }
            type(strong)
            val stale = waitForCandidate()
            instrumentation.runOnMainSync { editor.setSelection(0) }
            delay(250)
            stale.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            delay(100)
            instrumentation.runOnMainSync { assertEquals(strong, editor.text.toString()) }
            assertNull(candidate())
            type(strong)
            val disabled = waitForCandidate()
            CompletionSettings.setEnabled(app, false)
            delay(250)
            assertNull("Switching off hides the existing suggestion", candidate())
            disabled.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            delay(100)
            instrumentation.runOnMainSync { assertEquals(strong, editor.text.toString()) }
            CompletionSettings.setEnabled(app, true)
            instrumentation.runOnMainSync {
                editor.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                activity.getSystemService(InputMethodManager::class.java).restartInput(editor)
            }
            type("只用于测试的密码文本")
            delay(1000)
            assertNull("No completion in password fields", candidate())
            // Ordinary pinyin candidates still use the native select path after Qwen suggestions.
            instrumentation.runOnMainSync {
                editor.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
                activity.getSystemService(InputMethodManager::class.java).restartInput(editor)
            }
            delay(200)
            type("")
            for (key in "nihao") { instrumentation.sendStringSync(key.toString()); delay(100) }
            fun nativeCandidate(): AccessibilityNodeInfo? = automation.windows.asSequence().mapNotNull { it.root }
                .flatMap { it.findAccessibilityNodeInfosByViewId("${app.packageName}:id/candidate_view").asSequence() }
                .filter { it.isVisibleToUser }.flatMap { bar -> (0 until bar.childCount).mapNotNull(bar::getChild).asSequence() }
                .firstOrNull { it.isVisibleToUser && it.isClickable && it.viewIdResourceName != "${app.packageName}:id/completion_suggestion" }
            withTimeout(8000) { while (nativeCandidate() == null) delay(50) }
            delay(100)
            File(app.filesDir, "qwen-ui-native-pinyin.png").outputStream().use {
                checkNotNull(automation.takeScreenshot()).compress(Bitmap.CompressFormat.PNG, 100, it)
            }
            assertTrue(checkNotNull(nativeCandidate()).performAction(AccessibilityNodeInfo.ACTION_CLICK))
            var committed = false
            withTimeout(5000) { while (!committed) {
                instrumentation.runOnMainSync { committed = editor.text.any { it in '\u3400'..'\u9fff' } }
                delay(50)
            } }
            File(app.filesDir, "qwen-ui-perf-export.json").writeText(CompletionPerf.export(app))
        } finally {
            CompletionSettings.setBudgetMs(app, oldBudget)
            CompletionSettings.setEnabled(app, oldEnabled)
            CompletionSettings.setPolicy(app, oldPolicy)
            floatingMode.setValue(oldMode)
            instrumentation.runOnMainSync { activity.finish() }
        }
    }
}
