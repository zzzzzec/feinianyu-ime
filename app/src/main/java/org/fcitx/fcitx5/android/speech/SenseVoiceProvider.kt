// SPDX-License-Identifier: GPL-3.0-or-later
package org.fcitx.fcitx5.android.speech

import android.content.Context
import android.os.SystemClock
import com.k2fsa.sherpa.onnx.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

class SenseVoiceProvider(private val context: Context, private val language: String) : SpeechProvider {
    override val info = SpeechProviders.senseVoice
    private var recognizer: OfflineRecognizer? = null
    override suspend fun prepare() = withContext(Dispatchers.Default) {
        // The official ARM ORT initializer cannot run through the x86 emulator's Native Bridge.
        // Reject that combination before loading JNI so a wrong emulator APK cannot kill the IME.
        check(!(android.os.Build.SUPPORTED_ABIS.firstOrNull()?.startsWith("x86") == true &&
            context.applicationInfo.nativeLibraryDir.endsWith("/arm64"))) {
            context.getString(org.fcitx.fcitx5.android.R.string.speech_emulator_abi)
        }
        check(SpeechModelStore.ready(context)) { "请先下载语音模型" }
        recognizer = OfflineRecognizer(config = OfflineRecognizerConfig(
            featConfig = FeatureConfig(sampleRate = 16000, featureDim = 80),
            modelConfig = OfflineModelConfig(
                senseVoice = OfflineSenseVoiceModelConfig(
                    model = SpeechModelStore.file(context, "model.int8.onnx").absolutePath,
                    language = language, useInverseTextNormalization = true),
                tokens = SpeechModelStore.file(context, "tokens.txt").absolutePath,
                numThreads = 2, provider = "cpu", modelType = "sense_voice")))
    }
    override suspend fun recognize(audio: SpeechAudio): SpeechResult = withContext(Dispatchers.Default) {
        coroutineContext.ensureActive()
        val engine = checkNotNull(recognizer)
        val started = SystemClock.elapsedRealtime()
        val stream = engine.createStream()
        try {
            stream.acceptWaveform(audio.samples, audio.sampleRate)
            engine.decode(stream)
            coroutineContext.ensureActive()
            SpeechResult(SpeechTranscript.clean(engine.getResult(stream).text), audio.durationMs,
                SystemClock.elapsedRealtime() - started)
        } finally { stream.release() }
    }
    override fun close() { recognizer?.release(); recognizer = null }
}

object SpeechTranscript {
    // SenseVoice versions may include language/emotion/event markers; never insert those into an editor.
    fun clean(text: String) = text.replace(Regex("<\\|[^|]*\\|>"), "").trim()
    fun join(previous: String, next: String): String {
        if (previous.isEmpty() || next.isEmpty()) return previous + next
        val needsSpace = previous.last().isLetterOrDigit() && previous.last().code < 128 &&
            next.first().isLetterOrDigit() && next.first().code < 128
        return previous + (if (needsSpace) " " else "") + next
    }
}
