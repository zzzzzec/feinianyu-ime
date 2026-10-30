// SPDX-License-Identifier: GPL-3.0-or-later
package org.fcitx.fcitx5.android.completion

import androidx.annotation.Keep

/** Blocking operations must run on the single completion worker, never the IME/Fcitx thread. */
@Keep object QwenNative {
    init { System.loadLibrary("qwen-completion") }
    external fun load(path: String): ByteArray
    external fun generate(prompt: ByteArray, requestId: Long, maxTokens: Int, budgetMs: Int,
        minRoot: Double, minToken: Double, relativeRoot: Double, maxChars: Int, minChars: Int, minSingle: Double): ByteArray
    external fun cancel(requestId: Long)
    external fun reset()
    external fun unload()
}
