// SPDX-License-Identifier: GPL-3.0-or-later
package org.fcitx.fcitx5.android.completion

import androidx.annotation.Keep

@Keep object FixedDictionaryNative {
    init { System.loadLibrary("native-lib") }
    external fun convert(input: ByteArray, config: String): ByteArray
}
