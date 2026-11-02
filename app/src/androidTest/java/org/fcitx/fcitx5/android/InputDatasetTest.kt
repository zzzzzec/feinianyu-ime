// SPDX-License-Identifier: GPL-3.0-or-later
package org.fcitx.fcitx5.android

import android.content.Intent
import android.text.InputType
import android.view.accessibility.AccessibilityNodeInfo
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import org.fcitx.fcitx5.android.completion.*
import org.fcitx.fcitx5.android.core.*
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.dataset.InputDataset
import org.fcitx.fcitx5.android.daemon.FcitxDaemon
import org.fcitx.fcitx5.android.input.candidates.floating.FloatingCandidatesMode
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.zip.ZipInputStream

class InputDatasetTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private fun editor(type: Int = InputType.TYPE_CLASS_TEXT) = EditorInfo().apply { inputType = type; packageName = "dev.feinianyu.synthetic.dataset" }
    private suspend fun export(name: String): List<JSONObject> {
        val stream = ByteArrayOutputStream(); InputDataset.export(stream)
        val bytes = stream.toByteArray()
        File(context.getExternalFilesDir(null), name).writeBytes(bytes)
        ZipInputStream(bytes.inputStream()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (entry.name == "events.jsonl") return zip.readBytes().toString(Charsets.UTF_8).lineSequence().filter { it.isNotBlank() }.map { JSONObject(it) }.toList()
            }
        }
        error("events.jsonl missing")
    }
    @Test fun nativeCaptureAndControls(): Unit = runBlocking {
        InputDataset.initialize(context)
        val previous = InputDataset.enabled(context)
        val excluded = InputDataset.excluded()
        InputDataset.clear(); InputDataset.setExcluded(emptySet()); InputDataset.setEnabled(context, true)
        val fcitx = Fcitx(context)
        val commits = ConcurrentLinkedQueue<String>()
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        scope.launch(start = CoroutineStart.UNDISPATCHED) { fcitx.eventFlow.collect { if (it is FcitxEvent.CommitStringEvent) commits.add(it.data.text) } }
        try {
            fcitx.start(); withTimeout(30000) { while (!fcitx.isReady) delay(50) }
            fcitx.activate(27891, "dev.feinianyu.synthetic.dataset"); fcitx.setCapFlags(CapabilityFlags.DefaultFlags)
            fcitx.focus(true); fcitx.setEnabledIme(arrayOf("pinyin")); fcitx.activateIme("pinyin")
            val session = checkNotNull(InputDataset.session(context, editor()))
            fcitx.setSuzuContext("我明天去😀", false, true)
            fcitx.setInputRecording(session, true, "我明天去😀")
            for (key in "beijing") { fcitx.sendKey(key); delay(40) }; delay(100)
            val candidates = fcitx.getCandidates(0, 16).map { it.text }
            val selected = candidates.indexOf("北京"); assertTrue(candidates.toString(), selected >= 0)
            assertTrue(fcitx.select(selected))
            withTimeout(5000) { while (commits.isEmpty()) delay(20) }
            InputDataset.editorCommit(commits.poll(), "engine", true, 0, 0)
            val records = export("input-dataset-native-test.zip")
            val decision = records.last { it.optString("type") == "candidates" && it.getJSONObject("input").optString("raw_input") == "beijing" }
            assertEquals("我明天去😀", decision.getString("context"))
            val actual = (0 until decision.getJSONArray("candidates").length()).map { decision.getJSONArray("candidates").getJSONObject(it).getString("text") }
            assertEquals(candidates, actual)
            assertTrue(records.any { it.optString("type") == "editor_commit" && it.optString("text") == "北京" && it.optLong("engine_decision_id") > 0 })
            assertTrue(records.any { it.optString("type") == "action_result" && it.optJSONObject("action")?.optString("kind") == "candidate_click" })
            // Preserve actual typo input, without generating pinyin from the target.
            for (key in "beijng") { fcitx.sendKey(key); delay(40) }; delay(100)
            assertTrue(export("input-dataset-native-test.zip").any { it.optJSONObject("input")?.optString("raw_input") == "beijng" })
            val count = InputDataset.stats().first
            InputDataset.setEnabled(context, false)
            for (key in "abc") fcitx.sendKey(key)
            assertEquals(count, InputDataset.stats().first)
            InputDataset.setEnabled(context, true)
            assertNull(InputDataset.session(context, editor(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)))
            assertNull(InputDataset.session(context, editor().apply { imeOptions = EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING }))
            InputDataset.setExcluded(setOf(editor().packageName)); assertNull(InputDataset.session(context, editor()))
            assertEquals(count, InputDataset.stats().first)
            InputDataset.setExcluded(emptySet()); val owner = checkNotNull(InputDataset.session(context, editor()))
            repeat(50) { InputDataset.record(owner, JSONObject().put("type", "queued_before_clear")) }
            InputDataset.clear(); assertEquals(0L, InputDataset.stats().first)
            InputDataset.session(context, editor())
            InputDataset.event("persistence_marker")
            assertTrue(InputDataset.stats().first > 0)
        } finally { fcitx.stop(); scope.cancel(); InputDataset.setEnabled(context, previous); InputDataset.setExcluded(excluded) }
    }
    @Test fun persistedAndActualEditorCommit(): Unit = runBlocking {
        InputDataset.initialize(context)
        assertTrue("Run nativeCaptureAndControls in a separate invocation first", export("input-dataset-persistence-test.zip").any { it.optString("type") == "persistence_marker" })
        val previous = InputDataset.enabled(context)
        val mode = AppPrefs.getInstance().candidates.mode; val oldMode = mode.getValue()
        val completion = CompletionSettings.enabled(context)
        InputDataset.setEnabled(context, true); mode.setValue(FloatingCandidatesMode.Disabled); CompletionSettings.setEnabled(context, false)
        val automation = instrumentation.uiAutomation
        automation.executeShellCommand("ime set ${context.packageName}/org.fcitx.fcitx5.android.input.FcitxInputMethodService").close()
        automation.serviceInfo = automation.serviceInfo.apply { flags = flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS }
        val activity = instrumentation.startActivitySync(Intent(context, CompletionActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        val field = activity.findViewById<EditText>(R.id.completion_test_input)
        val connection = FcitxDaemon.connect("InputDatasetTest")
        try {
            instrumentation.runOnMainSync {
                field.requestFocus(); field.setText("我明天去"); field.setSelection(field.length())
                activity.getSystemService(InputMethodManager::class.java).showSoftInput(field, InputMethodManager.SHOW_IMPLICIT)
            }
            withTimeout(30000) { while (InputDataset.currentSession() == null) delay(100) }
            delay(1000)
            connection.runOnReady { activateIme("pinyin"); for (key in "beijing") { sendKey(key); delay(60) } }
            fun nodes(): List<AccessibilityNodeInfo> = automation.windows.mapNotNull { it.root }.flatMap { it.findAccessibilityNodeInfosByText("北京") }.filter { it.text?.toString() == "北京" && it.isVisibleToUser }
            withTimeout(15000) { while (nodes().isEmpty()) delay(100) }
            val button = generateSequence(nodes().first()) { it.parent }.first { it.isClickable }
            assertTrue(button.performAction(AccessibilityNodeInfo.ACTION_CLICK))
            withTimeout(5000) { while (!field.text.toString().endsWith("北京")) delay(50) }
            val events = export("input-dataset-editor-test.zip")
            assertTrue(events.any { it.optString("type") == "editor_commit" && it.optString("source") == "engine" && it.optString("text") == "北京" && it.optBoolean("success") })
            assertTrue(events.any { it.optString("type") == "candidates" && it.optJSONObject("input")?.optString("raw_input") == "beijing" })
        } finally {
            activity.finish(); FcitxDaemon.disconnect("InputDatasetTest")
            InputDataset.setEnabled(context, previous); mode.setValue(oldMode); CompletionSettings.setEnabled(context, completion)
        }
    }
}
