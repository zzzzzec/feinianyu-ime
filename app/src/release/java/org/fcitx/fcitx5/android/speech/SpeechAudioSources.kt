// SPDX-License-Identifier: GPL-3.0-or-later
package org.fcitx.fcitx5.android.speech

object SpeechAudioSources {
    fun create(): SpeechAudioSource = MicrophoneSource()
}
