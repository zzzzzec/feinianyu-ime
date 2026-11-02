// SPDX-License-Identifier: GPL-3.0-or-later
package org.fcitx.fcitx5.android.speech

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder

interface SpeechAudioSource : AutoCloseable {
    fun start()
    fun read(buffer: ShortArray): Int
    fun stop()
}

/** The source is owned by one capture job; stop unblocks a pending read, close runs in its finally. */
class MicrophoneSource : SpeechAudioSource {
    private var recorder: AudioRecord? = null
    @SuppressLint("MissingPermission")
    override fun start() {
        val minimum = AudioRecord.getMinBufferSize(16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        check(minimum > 0) { "麦克风不支持当前录音格式" }
        val value = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, 16000,
            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minimum * 4, 16384))
        recorder = value
        check(value.state == AudioRecord.STATE_INITIALIZED) { "无法打开麦克风" }
        value.startRecording()
        check(value.recordingState == AudioRecord.RECORDSTATE_RECORDING) { "麦克风录音未开始" }
    }
    override fun read(buffer: ShortArray) = recorder?.read(buffer, 0, buffer.size) ?: -1
    override fun stop() { runCatching { recorder?.stop() } }
    override fun close() { stop(); recorder?.release(); recorder = null }
}
