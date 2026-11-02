// SPDX-License-Identifier: GPL-3.0-or-later
package org.fcitx.fcitx5.android.speech

import android.content.Context
import android.os.SystemClock
import com.k2fsa.sherpa.onnx.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import kotlin.math.sqrt

enum class SpeechPhase { IDLE, LOADING, LISTENING, RECOGNIZING, FINISHED, ERROR }
data class SpeechUiState(val phase: SpeechPhase = SpeechPhase.IDLE, val text: String = "",
    val capturing: Boolean = false, val level: Float = 0f, val error: String = "")

/** One session owns its microphone, VAD and provider. Native decode is serial and never on the UI thread. */
class SpeechController(
    private val context: Context,
    private val scope: CoroutineScope,
    private val onText: (String) -> Boolean,
    private val sourceFactory: () -> SpeechAudioSource = { MicrophoneSource() },
    private val providerFactory: () -> SpeechProvider = {
        SpeechProviders.create(context, SpeechSettings.provider(context), SpeechSettings.language(context))
    },
    private val beforePrepare: suspend () -> Unit = {}
) {
    private val mutableState = MutableStateFlow(SpeechUiState())
    val state = mutableState.asStateFlow()
    private var job: Job? = null
    private var generation = 0L
    @Volatile private var stopRequested = false
    @Volatile private var source: SpeechAudioSource? = null
    val active get() = job?.isCompleted == false

    fun start() {
        if (active) return
        val id = ++generation
        stopRequested = false
        mutableState.value = SpeechUiState(SpeechPhase.LOADING)
        job = scope.launch {
            val started = SystemClock.elapsedRealtime()
            var provider: SpeechProvider? = null
            var vad: Vad? = null
            try {
                beforePrepare()
                ensureActive()
                val handoverAt = SystemClock.elapsedRealtime()
                provider = providerFactory()
                provider.prepare()
                ensureActive()
                val modelLoadedAt = SystemClock.elapsedRealtime()
                vad = withContext(Dispatchers.Default) { Vad(config = VadModelConfig(
                    sileroVadModelConfig = SileroVadModelConfig(
                        model = SpeechModelStore.file(context, "silero_vad.onnx").absolutePath,
                        minSilenceDuration = 0.7f, minSpeechDuration = 0.25f, maxSpeechDuration = 12f),
                    sampleRate = 16000, numThreads = 1)) }
                SpeechPerf.record(context, "load", JSONObject().put("elapsed_ms", SystemClock.elapsedRealtime() - started)
                    .put("handover_ms", handoverAt - started).put("model_load_ms", modelLoadedAt - handoverAt)
                    .put("vad_load_ms", SystemClock.elapsedRealtime() - modelLoadedAt).put("provider", provider.info.id))
                if (stopRequested) { mutableState.value = SpeechUiState(SpeechPhase.FINISHED); return@launch }
                val activeProvider = provider
                val activeVad = vad
                coroutineScope {
                    // Only complete speech segments cross this boundary. Bound memory and stop on overload.
                    val segments = Channel<Pair<SpeechAudio, Long>>(3)
                    val decoder = launch {
                        for ((audio, endedAt) in segments) {
                            val textBefore = mutableState.value.text
                            mutableState.value = mutableState.value.copy(phase = SpeechPhase.RECOGNIZING)
                            val result = activeProvider.recognize(audio)
                            ensureActive()
                            if (generation != id) throw CancellationException("stale speech session")
                            val joined = SpeechTranscript.join(textBefore, result.text)
                            val insertion = joined.substring(textBefore.length)
                            val applied = insertion.isEmpty() || onText(insertion)
                            SpeechPerf.record(context, "recognition", JSONObject().put("audio_ms", result.audioMs)
                                .put("provider", activeProvider.info.id)
                                .put("inference_ms", result.inferenceMs)
                                .put("endpoint_to_result_ms", SystemClock.elapsedRealtime() - endedAt)
                                .put("rtf", result.inferenceMs.toDouble() / result.audioMs.coerceAtLeast(1))
                                .put("output_chars", result.text.codePointCount(0, result.text.length)).put("applied", applied))
                            if (!applied) throw CancellationException("editor changed")
                            mutableState.value = mutableState.value.copy(text = joined,
                                phase = if (mutableState.value.capturing) SpeechPhase.LISTENING else SpeechPhase.RECOGNIZING)
                        }
                    }
                    try {
                        withContext(Dispatchers.IO) {
                            val microphone = sourceFactory()
                            source = microphone
                            var sampleCount = 0L
                            var lastSpeech = 0L
                            var lastMeter = 0L
                            fun drain() {
                                while (!activeVad.empty()) {
                                    val segment = activeVad.front()
                                    activeVad.pop()
                                    check(segments.trySend(SpeechAudio(segment.samples) to SystemClock.elapsedRealtime()).isSuccess) {
                                        "识别暂时跟不上，请分段说话"
                                    }
                                }
                            }
                            try {
                                if (!stopRequested) microphone.start()
                                withContext(Dispatchers.Main) {
                                    if (generation == id && !stopRequested) mutableState.value = SpeechUiState(SpeechPhase.LISTENING, capturing = true)
                                }
                                val buffer = ShortArray(512)
                                while (isActive && !stopRequested) {
                                    val count = microphone.read(buffer)
                                    if (stopRequested) break
                                    check(count >= 0) { "录音中断，请重试" }
                                    if (count == 0) continue
                                    val samples = FloatArray(count) { buffer[it] / 32768f }
                                    activeVad.acceptWaveform(samples)
                                    sampleCount += count
                                    if (activeVad.isSpeechDetected()) lastSpeech = sampleCount
                                    drain()
                                    val now = SystemClock.elapsedRealtime()
                                    if (now - lastMeter >= 100) {
                                        val level = (sqrt(samples.sumOf { (it * it).toDouble() } / count) * 7).toFloat().coerceIn(0f, 1f)
                                        withContext(Dispatchers.Main) {
                                            if (generation == id) mutableState.value = mutableState.value.copy(level = level)
                                        }
                                        lastMeter = now
                                    }
                                    if (sampleCount >= 120L * 16000 || sampleCount - lastSpeech > 10L * 16000) break
                                }
                                ensureActive()
                                activeVad.flush()
                                drain()
                            } finally { microphone.close(); source = null }
                        }
                    } finally { segments.close() }
                    mutableState.value = mutableState.value.copy(capturing = false, level = 0f, phase = SpeechPhase.RECOGNIZING)
                    decoder.join()
                }
                mutableState.value = mutableState.value.copy(phase = SpeechPhase.FINISHED)
                SpeechPerf.record(context, "session", JSONObject().put("state", "finished")
                    .put("elapsed_ms", SystemClock.elapsedRealtime() - started))
            } catch (error: CancellationException) {
                if (generation == id) mutableState.value = SpeechUiState()
                withContext(NonCancellable) { SpeechPerf.record(context, "session", JSONObject().put("state", "cancelled")) }
                throw error
            } catch (error: Exception) {
                if (generation == id) mutableState.value = mutableState.value.copy(phase = SpeechPhase.ERROR,
                    capturing = false, level = 0f, error = error.localizedMessage.orEmpty())
                SpeechPerf.record(context, "session", JSONObject().put("state", "error").put("error_type", error.javaClass.simpleName))
            } finally {
                withContext(NonCancellable + Dispatchers.Default) { vad?.release(); provider?.close() }
            }
        }
    }
    /** Finish keeps queued segments; cancellation discards all late results. */
    fun finish() { stopRequested = true; source?.stop() }
    fun cancel() {
        generation++
        stopRequested = true
        source?.stop()
        job?.cancel()
        mutableState.value = SpeechUiState()
    }
}

/** Prevents late recognition from writing into another field or a moved selection. */
class SpeechEditorGuard {
    private var editor = -1L
    private var start = -1
    private var end = -1
    fun begin(epoch: Long, selectionStart: Int, selectionEnd: Int) { editor = epoch; start = selectionStart; end = selectionEnd }
    fun accepts(epoch: Long, selectionStart: Int, selectionEnd: Int) = editor >= 0 && editor == epoch && start == selectionStart && end == selectionEnd
    fun committed(selectionStart: Int, selectionEnd: Int) { start = selectionStart; end = selectionEnd }
    fun invalidate() { editor = -1 }
}
