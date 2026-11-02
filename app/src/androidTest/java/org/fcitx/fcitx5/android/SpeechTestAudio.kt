// SPDX-License-Identifier: GPL-3.0-or-later
package org.fcitx.fcitx5.android

import android.content.Context
import org.fcitx.fcitx5.android.speech.SpeechAudioSource
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

object SpeechTestAudio {
    fun samples(context: Context): ShortArray {
        val bytes = File(context.getExternalFilesDir("speech-qa"), "zh.wav").readBytes()
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        var offset = 12
        while (offset + 8 <= bytes.size) {
            val name = String(bytes, offset, 4, Charsets.US_ASCII)
            val size = buffer.getInt(offset + 4)
            if (name == "fmt ") {
                check(buffer.getShort(offset + 10).toInt() == 1)
                check(buffer.getInt(offset + 12) == 16000)
                check(buffer.getShort(offset + 22).toInt() == 16)
            }
            if (name == "data") return ShortArray(size / 2) { buffer.getShort(offset + 8 + it * 2) }
            offset += 8 + size + (size and 1)
        }
        error("WAV data missing")
    }
    class Source(private val samples: ShortArray) : SpeechAudioSource {
        private var position = 0
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
        override fun close() {}
    }
}
