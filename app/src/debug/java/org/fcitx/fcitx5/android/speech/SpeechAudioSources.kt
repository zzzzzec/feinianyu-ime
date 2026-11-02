// SPDX-License-Identifier: GPL-3.0-or-later
package org.fcitx.fcitx5.android.speech

/** Debug-only injection: UI tests feed public fixtures through the real provider and editor pipeline. */
object SpeechAudioSources {
    var factory: () -> SpeechAudioSource = { MicrophoneSource() }
    fun create() = factory()
}
