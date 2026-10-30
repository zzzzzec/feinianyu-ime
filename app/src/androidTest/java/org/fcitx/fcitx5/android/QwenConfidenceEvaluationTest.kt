// SPDX-License-Identifier: GPL-3.0-or-later
package org.fcitx.fcitx5.android

import android.util.Base64
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.fcitx.fcitx5.android.completion.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** Quality diagnostic, not an accuracy acceptance test. Fixed answers never reach the generator. */
class QwenConfidenceEvaluationTest {
    private fun checkGates(result: JSONObject, policy: CompletionPolicy) {
        assertTrue(result.toString(), result.getString("state") in listOf("generated", "abstained"))
        val candidates = result.getJSONArray("candidates")
        val scores = result.getJSONArray("candidate_scores")
        assertEquals(candidates.length(), scores.length())
        assertTrue(candidates.length() <= 3)
        if (result.getString("state") == "abstained") assertEquals(0, candidates.length())
        for (index in 0 until candidates.length()) {
            val score = scores.getJSONObject(index)
            val probabilities = score.getJSONArray("token_probabilities")
            assertTrue(score.getDouble("root_p") + 1e-8 >= policy.minRoot)
            assertTrue(score.getDouble("root_p") + 1e-8 >= policy.relativeRoot * result.getDouble("root_top_p"))
            if (score.getInt("body_chars") < policy.minChars) assertTrue(score.getDouble("root_p") + 1e-8 >= policy.minSingle)
            for (token in 1 until probabilities.length()) assertTrue(probabilities.getDouble(token) + 1e-8 >= policy.minToken)
            val text = candidates.getString(index)
            assertTrue(text.codePointCount(0, text.length) <= policy.maxChars)
            if (score.getString("stop_reason") == "low_token_confidence") assertTrue(score.getDouble("stop_token_p") < policy.minToken)
        }
    }

    @Test fun evaluateKnownNextLines(): Unit = runBlocking {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        QwenCompletionEngine.setActive(app, true)
        try {
            withTimeout(120000) { while (QwenCompletionEngine.state.value !in listOf("ready", "error")) delay(100) }
            assertEquals("ready", QwenCompletionEngine.state.value)
            val poems = listOf(
                "床前明月光" to "疑是地上霜", "白日依山尽" to "黄河入海流",
                "春眠不觉晓" to "处处闻啼鸟", "两个黄鹂鸣翠柳" to "一行白鹭上青天",
                "明月松间照" to "清泉石上流", "春风又绿江南岸" to "明月何时照我还",
                "海内存知己" to "天涯若比邻", "欲穷千里目" to "更上一层楼"
            )
            val outputs = JSONArray()
            fun normalize(text: String) = text.filter { it.isLetterOrDigit() }
            for (policy in CompletionConfidence.policies) for ((first, expected) in poems) for (ending in listOf("", "，")) {
                val prefix = first + ending
                QwenNative.reset()
                val result = QwenCompletionEngine.generate(app, prefix, QwenCompletionEngine.nextId(), 30000, policy)
                checkGates(result, policy)
                val candidates = result.optJSONArray("candidates") ?: JSONArray()
                val grades = JSONArray()
                for (index in 0 until candidates.length()) {
                    val candidate = normalize(CompletionText.suggestion(candidates.getString(index)))
                    grades.put(when {
                        candidate.isEmpty() -> "empty"
                        candidate == expected -> "exact"
                        expected.startsWith(candidate) -> "correct_partial"
                        candidate.startsWith(expected) -> "overrun"
                        else -> "wrong"
                    })
                }
                outputs.put(JSONObject().put("policy", policy.name).put("format", if (ending.isEmpty()) "plain" else "comma")
                    .put("prefix", prefix).put("expected", expected)
                    .put("grades", grades).put("result", result))
            }
            val extra = InstrumentationRegistry.getArguments().getString("extra_prefix_b64")
            val chats = listOf("今天辛苦了，早点", "这份文件我看过了，有几个地方需要", "明天下午我有空，我们可以")
            for (policy in CompletionConfidence.policies) {
                for (prefix in chats) {
                    QwenNative.reset()
                    val result = QwenCompletionEngine.generate(app, prefix, QwenCompletionEngine.nextId(), 30000, policy)
                    checkGates(result, policy)
                    outputs.put(JSONObject().put("policy", policy.name).put("chat_probe", true).put("prefix", prefix).put("result", result))
                }
                if (extra != null) {
                    val prefix = String(Base64.decode(extra, Base64.DEFAULT), Charsets.UTF_8)
                    QwenNative.reset()
                    val result = QwenCompletionEngine.generate(app, prefix, QwenCompletionEngine.nextId(), 30000, policy)
                    checkGates(result, policy)
                    outputs.put(JSONObject().put("policy", policy.name).put("private_probe", true).put("prefix", prefix).put("result", result))
                }
            }
            File(app.filesDir, "qwen-confidence-evaluation.json").writeText(JSONObject()
                .put("model_id", QwenModelStore.ID).put("app_version", BuildConfig.VERSION_NAME)
                .put("build_commit", BuildConfig.BUILD_GIT_HASH).put("cases", outputs).toString(2))
        } finally { QwenCompletionEngine.setActive(app, false) }
    }

    @Test fun strictGateAbstainsAndUncertainNextTokenIsNotAppended(): Unit = runBlocking {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        QwenCompletionEngine.setActive(app, true)
        try {
            withTimeout(120000) { while (QwenCompletionEngine.state.value !in listOf("ready", "error")) delay(100) }
            assertEquals("ready", QwenCompletionEngine.state.value)
            val strict = CompletionPolicy("test_strict", 1.0, 1.0, 1.0)
            val empty = QwenCompletionEngine.generate(app, "春眠不觉晓，", QwenCompletionEngine.nextId(), 30000, strict)
            checkGates(empty, strict)
            assertEquals("abstained", empty.getString("state"))
            val stop = CompletionPolicy("test_stop", 0.0, 0.999, 0.0, 12, 1)
            val truncated = QwenCompletionEngine.generate(app, "春眠不觉晓，", QwenCompletionEngine.nextId(), 30000, stop)
            checkGates(truncated, stop)
            val first = truncated.getJSONArray("candidate_scores").getJSONObject(0)
            assertEquals(1, first.getJSONArray("token_probabilities").length())
            assertEquals("low_token_confidence", first.getString("stop_reason"))
            assertTrue(first.getDouble("stop_token_p") < stop.minToken)
        } finally { QwenCompletionEngine.setActive(app, false) }
    }
}
