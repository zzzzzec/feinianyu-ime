// SPDX-License-Identifier: GPL-3.0-or-later
package org.fcitx.fcitx5.android.input

import android.text.InputType
import android.view.inputmethod.EditorInfo

object SuzuContextPolicy {
    fun allows(info: EditorInfo?): Boolean {
        if (info == null || info.imeOptions and EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING != 0) return false
        if (info.inputType and InputType.TYPE_MASK_CLASS != InputType.TYPE_CLASS_TEXT) return false
        return when (info.inputType and InputType.TYPE_MASK_VARIATION) {
            InputType.TYPE_TEXT_VARIATION_PASSWORD,
            InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
            InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD -> false
            else -> true
        }
    }
}
