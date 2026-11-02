// SPDX-License-Identifier: GPL-3.0-or-later
package org.fcitx.fcitx5.android

import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.fcitx.fcitx5.android.speech.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Provision the three pinned model files and upstream zh.wav in externalFilesDir/speech-qa.
 * Fixtures are not bundled in the APK; this test exercises the actual Android native runtime.
 */
class SpeechRecognitionTest {
    private val app get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun provision() {
        if (!SpeechModelStore.ready(app)) for (item in SpeechModelStore.files) {
            SpeechModelStore.installFile(app, File(app.getExternalFilesDir("speech-qa"), item.name), item)
        }
        assertTrue(SpeechModelStore.ready(app))
    }
    private fun samples(): ShortArray {
        val bytes = File(app.getExternalFilesDir("speech-qa"), "zh.wav").readBytes()
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        var offset = 12
        while (offset + 8 <= bytes.size) {
            val name = String(bytes, offset, 4, Charsets.US_ASCII)
            val size = buffer.getInt(offset + 4)
            if (name == "fmt ") { assertEquals(1, buffer.getShort(offset + 10).toInt()); assertEquals(16000, buffer.getInt(offset + 12)); assertEquals(16, buffer.getShort(offset + 22).toInt()) }
            if (name == "data") return ShortArray(size / 2) { buffer.getShort(offset + 8 + it * 2) }
            offset += 8 + size + (size and 1)
        }
        error("WAV data missing")
    }
    private class SampleSource(private val samples: ShortArray) : SpeechAudioSource {
        private var position = 0
        @Volatile var closed = false
        override fun start() {}
        override fun read(buffer: ShortArray): Int {
            Thread.sleep(2)
            buffer.fill(0)
            val count = minOf(buffer.size, (samples.size - position).coerceAtLeast(0))
            if (count > 0) samples.copyInto(buffer, 0, position, position + count)
            position += buffer.size
            return buffer.size
        }
        override fun stop() {}
        override fun close() { closed = true }
    }
    @Test fun realChineseModelAndSegmentPipeline(): Unit = runBlocking {
        provision()
        val pcm = samples()
        val provider = SenseVoiceProvider(app, "auto")
        val started = android.os.SystemClock.elapsedRealtime()
        var expectedText = ""
        try {
            provider.prepare()
            val loadMs = android.os.SystemClock.elapsedRealtime() - started
            val result = provider.recognize(SpeechAudio(FloatArray(pcm.size) { pcm[it] / 32768f }))
            println("SPEECH_SAMPLE_RESULT: ${result.text}")
            val reference = "开放时间早上9点至下午5点。"
            val errors = editDistance(reference, result.text)
            expectedText = result.text
            File(app.filesDir, "speech-model-proof.json").writeText(JSONObject().put("model", SpeechModelStore.ID)
                .put("text", result.text).put("load_ms", loadMs).put("audio_ms", result.audioMs)
                .put("reference", reference).put("reference_source", "upstream documented ITN example output")
                .put("char_errors", errors).put("cer", errors.toDouble() / reference.length)
                .put("inference_ms", result.inferenceMs).put("fixture", "upstream public zh.wav").toString(2))
            // A smoke gate for gross runtime/decoding regressions, not a claim of perfect ASR accuracy.
            assertTrue(result.text, errors.toDouble() / reference.length <= 0.15)
            assertFalse(result.text.contains("<|"))
            println("SPEECH_NATIVE_PROOF: ${result.text}; audio=${result.audioMs}ms inference=${result.inferenceMs}ms load=${loadMs}ms")
        } finally { provider.close() }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        val source = SampleSource(pcm)
        val outputs = mutableListOf<String>()
        val controller = SpeechController(app, scope, { outputs += it; true }, { source })
        try {
            withContext(Dispatchers.Main) { controller.start() }
            withTimeout(180000) { while (controller.active) delay(50) }
            assertEquals(controller.state.value.toString(), SpeechPhase.FINISHED, controller.state.value.phase)
            assertEquals("VAD segmentation must preserve the model's complete transcription", expectedText, outputs.joinToString(""))
            assertTrue(source.closed)
            val perf = SpeechPerf.export(app)
            assertFalse(perf.contains("开放时间"))
            File(app.filesDir, "speech-perf-proof.json").writeText(perf)
        } finally { withContext(Dispatchers.Main) { controller.cancel() }; scope.cancel() }
    }
    private fun editDistance(a: String, b: String): Int {
        var previous = IntArray(b.length + 1) { it }
        for (i in a.indices) {
            val row = IntArray(b.length + 1); row[0] = i + 1
            for (j in b.indices) row[j + 1] = minOf(row[j] + 1, previous[j + 1] + 1, previous[j] + if (a[i] == b[j]) 0 else 1)
            previous = row
        }
        return previous[b.length]
    }
    @Test fun cancelledNativeWorkNeverCommitsLateText(): Unit = runBlocking {
        provision()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        val decoding = CompletableDeferred<Unit>()
        val outputs = mutableListOf<String>()
        val source = SampleSource(samples())
        val fake = object : SpeechProvider {
            override val info = SpeechProviders.senseVoice
            override suspend fun prepare() {}
            override suspend fun recognize(audio: SpeechAudio): SpeechResult {
                decoding.complete(Unit)
                // Native code may return after coroutine cancellation; the controller must reject it.
                withContext(NonCancellable) { delay(500) }
                return SpeechResult("迟到结果", audio.durationMs, 500)
            }
            override fun close() {}
        }
        val controller = SpeechController(app, scope, { outputs += it; true }, { source }, { fake })
        try {
            withContext(Dispatchers.Main) { controller.start() }
            withTimeout(30000) { decoding.await() }
            withContext(Dispatchers.Main) { controller.cancel() }
            withTimeout(10000) { while (controller.active) delay(50) }
            assertTrue(outputs.isEmpty())
            assertTrue(source.closed)
        } finally { scope.cancel() }
    }
    @Test fun corruptReplacementPreservesInstalledModel() {
        provision()
        val item = SpeechModelStore.files.first { it.name == "tokens.txt" }
        val target = SpeechModelStore.file(app, item.name)
        val original = target.readBytes()
        val corrupt = File(app.cacheDir, "speech-corrupt.txt").apply { writeBytes(ByteArray(item.size.toInt())) }
        try {
            assertTrue(runCatching { SpeechModelStore.installFile(app, corrupt, item) }.isFailure)
            assertArrayEquals(original, target.readBytes())
            assertTrue(SpeechModelStore.ready(app))
        } finally { corrupt.delete() }
    }
}
