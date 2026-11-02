// SPDX-License-Identifier: GPL-3.0-or-later
package org.fcitx.fcitx5.android.speech

import android.content.Context

data class SpeechAudio(val samples: FloatArray, val sampleRate: Int = 16000) {
    val durationMs: Long get() = samples.size * 1000L / sampleRate
}

data class SpeechResult(val text: String, val audioMs: Long, val inferenceMs: Long)
enum class SpeechExecution { LOCAL, REMOTE }
data class SpeechProviderInfo(val id: String, val name: String, val execution: SpeechExecution)

/** Segment recognition contract. Recording, segmentation, editor lifetime and UI belong to the caller.
 * A remote implementation can upload the same audio without changing the keyboard or recorder.
 * True streaming recognition should implement a separate session contract, not pretend batch is streaming.
 */
interface SpeechProvider : AutoCloseable {
    val info: SpeechProviderInfo
    suspend fun prepare()
    suspend fun recognize(audio: SpeechAudio): SpeechResult
}

object SpeechProviders {
    val senseVoice = SpeechProviderInfo("sensevoice-local", "SenseVoice Small · 本地", SpeechExecution.LOCAL)
    val available = listOf(senseVoice)
    fun ready(context: Context, id: String = SpeechSettings.provider(context)): Boolean = when (id) {
        senseVoice.id -> SpeechModelStore.ready(context)
        else -> false
    }
    fun create(context: Context, id: String, language: String): SpeechProvider = when (id) {
        senseVoice.id -> SenseVoiceProvider(context.applicationContext, language)
        else -> error("未找到语音识别服务：$id")
    }
}
