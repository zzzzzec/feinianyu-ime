// SPDX-License-Identifier: GPL-3.0-or-later
package org.fcitx.fcitx5.android

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.graphics.Bitmap
import android.text.InputType
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.fcitx.fcitx5.android.completion.*
import org.fcitx.fcitx5.android.core.*
import org.fcitx.fcitx5.android.data.pinyin.*
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.input.candidates.floating.FloatingCandidatesMode
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class FixedCompletionTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val app get() = instrumentation.targetContext
    @Test fun originalTextAndPinyinPacks(): Unit = runBlocking {
        val fcitx = Fcitx(app)
        try {
            fcitx.start(); withTimeout(30000) { while (!fcitx.isReady) delay(50) }
            val outputs = JSONArray()
            for ((prefix, expected) in listOf(
                "四海无闲田" to "，农夫犹饿死。", "四海无闲田，" to "农夫犹饿死。",
                "四海無閒田" to "，農夫猶餓死。", "我想说四海无闲田" to "，农夫犹饿死。",
                "床前明月光" to "，疑是地上霜。", "白日依山尽" to "，黄河入海流。",
                "春种一粒粟，秋成万颗子" to "。四海无闲田，",
                "两个黄鹂鸣翠柳" to "，一行白鹭上青天。", "明月几时有" to "，把酒问青天。",
                "百尺竿头" to "更进一步")) {
                val result = FixedCompletionEngine.lookup(app, prefix, QwenCompletionEngine.nextId())
                assertTrue(result.toString(), result.getBoolean("matched"))
                assertTrue(result.toString(), (0 until result.getJSONArray("candidates").length()).any { result.getJSONArray("candidates").getString(it) == expected })
                assertFalse(result.has("root_p"))
                outputs.put(JSONObject().put("prefix", prefix).put("result", result))
            }
            assertFalse(FixedCompletionEngine.lookup(app, "我今天下班之后", QwenCompletionEngine.nextId()).getBoolean("matched"))
            assertFalse(FixedCompletionEngine.lookup(app, "四海无闲天", QwenCompletionEngine.nextId()).getBoolean("matched"))
            assertEquals("finished", FixedCompletionEngine.lookup(app, "四海无闲田，农夫犹饿死。", QwenCompletionEngine.nextId()).getString("state"))
            assertEquals(listOf("，一行白鹭上青天。"), CompletionText.suggestions(listOf("，一行白鹭上青天。"), 32))
            val packs = PinyinDictManager.listDictionaries().filter { it.name.startsWith("飞念语-") }
            assertEquals(packs.toString(), 4, packs.size)
            val samples = mapOf("计算机" to "虚拟地址 xu'ni'di'zhi", "历史人名" to "荀彧 xun'yu", "地名" to "北京 bei'jing", "成语" to "实事求是 shi'shi'qiu'shi")
            for ((category, sample) in samples) {
                val pack = packs.first { it.name.endsWith(category) }
                val dump = File(app.cacheDir, "check-$category.txt")
                try {
                    PinyinDictManager.pinyinDictConv(pack.file.absolutePath, dump.absolutePath, true)
                    assertTrue(category, dump.readText().contains(sample))
                } finally { dump.delete() }
            }
            // A deleted bundled pack must not be silently installed again.
            val pack = packs.first(); val saved = File(pack.file.path + ".qa-backup")
            check(pack.file.renameTo(saved))
            try { BundledPinyinDictionaries.install(app); assertFalse(pack.file.exists()) }
            finally { check(saved.renameTo(pack.file)) }
            fcitx.activate(24803, "dev.feinianyu.fixed-dictionary.synthetic")
            fcitx.setCapFlags(CapabilityFlags.DefaultFlags); fcitx.focus(true)
            fcitx.setEnabledIme(arrayOf("pinyin")); fcitx.activateIme("pinyin")
            for ((code, expected) in listOf("jiansuozengqiangshengcheng" to "检索增强生成", "xunyu" to "荀彧", "beijing" to "北京")) {
                fcitx.reset()
                for (key in code) { fcitx.sendKey(key); delay(20) }
                val values = fcitx.getCandidates(0, 20).map { it.text }
                assertTrue("$expected missing from $values", expected in values)
            }
            fcitx.reset()
            File(app.filesDir, "fixed-dictionary-results.json").writeText(outputs.toString(2))
        } finally { fcitx.stop() }
    }
    @Test fun originalCandidateBarCommitsDictionaryAndMissRunsQwen(): Unit = runBlocking {
        val enabled = CompletionSettings.enabled(app); val dictionary = CompletionSettings.dictionaryEnabled(app)
        val budget = CompletionSettings.budgetMs(app)
        val mode = AppPrefs.getInstance().candidates.mode; val oldMode = mode.getValue()
        CompletionSettings.setEnabled(app, true); CompletionSettings.setDictionaryEnabled(app, true)
        CompletionSettings.setBudgetMs(app, 10000); mode.setValue(FloatingCandidatesMode.Disabled)
        val automation = instrumentation.uiAutomation
        automation.executeShellCommand("ime set ${app.packageName}/org.fcitx.fcitx5.android.input.FcitxInputMethodService").close()
        automation.serviceInfo = automation.serviceInfo.apply { flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS }
        val activity = instrumentation.startActivitySync(Intent(app, CompletionActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        val editor = activity.findViewById<EditText>(R.id.completion_test_input)
        fun candidates(): List<AccessibilityNodeInfo> = automation.windows.asSequence().mapNotNull { it.root }
            .flatMap { it.findAccessibilityNodeInfosByViewId("${app.packageName}:id/completion_suggestion").asSequence() }
            .filter { it.isVisibleToUser && !it.contentDescription.isNullOrBlank() }.toList()
        fun type(text: String) = instrumentation.runOnMainSync {
            editor.requestFocus(); editor.setText(text); editor.setSelection(text.length)
            activity.getSystemService(InputMethodManager::class.java).showSoftInput(editor, InputMethodManager.SHOW_IMPLICIT)
        }
        try {
            delay(1000)
            val hitStart = JSONObject(CompletionPerf.export(app)).getJSONArray("records").length()
            type("四海无闲田")
            withTimeout(15000) { while (candidates().none { it.contentDescription.toString() == "，农夫犹饿死。" }) delay(50) }
            val candidate = candidates().first { it.contentDescription.toString() == "，农夫犹饿死。" }
            assertTrue(generateSequence(candidate) { it.parent }.any { it.viewIdResourceName == "${app.packageName}:id/candidate_view" })
            automation.takeScreenshot()?.let { bitmap -> File(app.getExternalFilesDir(null), "fixed-completion-ui.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) } }
            assertTrue(candidate.performAction(AccessibilityNodeInfo.ACTION_CLICK))
            withTimeout(5000) { while (editor.text.toString() != "四海无闲田，农夫犹饿死。") delay(50) }
            delay(500); assertTrue("No continuation beyond end of poem", candidates().isEmpty())
            val hitRecords = JSONObject(CompletionPerf.export(app)).getJSONArray("records")
            assertTrue((hitStart until hitRecords.length()).any { hitRecords.getJSONObject(it).optString("source") == "dictionary" })
            assertFalse("Dictionary hits must not run Qwen inference", (hitStart until hitRecords.length()).any { hitRecords.getJSONObject(it).optString("phase") == "inference" })
            QwenCompletionEngine.ensureLoaded(app)
            withTimeout(60000) { while (QwenCompletionEngine.state.value != "ready") delay(100) }
            val before = JSONObject(CompletionPerf.export(app)).getJSONArray("records").length()
            type("我今天下班之后")
            var export = ""
            withTimeout(20000) {
                while (true) {
                    export = CompletionPerf.export(app)
                    val records = JSONObject(export).getJSONArray("records")
                    if ((before until records.length()).any { records.getJSONObject(it).optString("phase") == "inference" }) break
                    delay(100)
                }
            }
            assertFalse(export.contains("四海无闲田")); assertFalse(export.contains("农夫犹饿死"))
            File(app.filesDir, "fixed-completion-perf.json").writeText(export)
            type("四海无闲田"); delay(700)
            instrumentation.runOnMainSync { editor.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD }
            delay(500); assertTrue("Password editors must not display completions", candidates().isEmpty())
        } finally {
            CompletionSettings.setEnabled(app, enabled); CompletionSettings.setDictionaryEnabled(app, dictionary)
            CompletionSettings.setBudgetMs(app, budget); mode.setValue(oldMode)
            instrumentation.runOnMainSync { activity.finish() }
        }
    }
}
