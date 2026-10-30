// SPDX-License-Identifier: GPL-3.0-or-later
package org.fcitx.fcitx5.android.completion

/** Raw next-token likelihood gates, not a calibrated probability that a phrase is correct. */
data class CompletionPolicy(
    val name: String, val minRoot: Double, val minToken: Double, val relativeRoot: Double,
    val maxChars: Int = 8, val minChars: Int = 2, val minSingle: Double = 0.50
)

object CompletionConfidence {
    val policies = listOf(
        CompletionPolicy("conservative", 0.15, 0.40, 0.35),
        CompletionPolicy("balanced", 0.10, 0.30, 0.25, minSingle = 0.40),
        CompletionPolicy("relaxed", 0.04, 0.20, 0.15, minSingle = 0.30)
    )
    fun policy(name: String?) = policies.firstOrNull { it.name == name } ?: policies.first()
}
