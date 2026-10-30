// SPDX-License-Identifier: GPL-3.0-or-later
package org.fcitx.fcitx5.android.completion

/** Unicode code points, not UTF-16 code units. Never join separate editors or messages. */
object CompletionText {
    const val MAX_SUGGESTIONS = 3
    fun suffix(text: String, maxChars: Int): String {
        val count = text.codePointCount(0, text.length)
        return if (count <= maxChars) text else text.substring(text.offsetByCodePoints(0, count - maxChars))
    }
    fun suggestion(raw: String, maxChars: Int = 8): String {
        val text = raw.substringBefore('\n').substringBefore('\r').substringBefore("\\n").substringBefore("\\r").substringBefore("<|").trimEnd()
        if (text.isBlank() || text.contains('\uFFFD') || text.any { it.isISOControl() }) return ""
        val count = text.codePointCount(0, text.length).coerceAtMost(maxChars)
        return text.substring(0, text.offsetByCodePoints(0, count))
    }
    fun suggestions(raw: List<String>): List<String> = raw.asSequence()
        .map { suggestion(it) }.filter { it.isNotBlank() }.distinct().take(MAX_SUGGESTIONS).toList()
}
