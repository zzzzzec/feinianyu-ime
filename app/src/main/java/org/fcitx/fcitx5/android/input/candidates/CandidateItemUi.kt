/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2023 Fcitx5 for Android Contributors
 */

package org.fcitx.fcitx5.android.input.candidates

import android.content.Context
import android.content.res.ColorStateList
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.core.text.buildSpannedString
import androidx.core.text.color
import androidx.core.view.ViewCompat
import androidx.core.view.isVisible
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.core.CandidateWord
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.theme.Theme
import org.fcitx.fcitx5.android.input.AutoScaleTextView
import org.fcitx.fcitx5.android.input.keyboard.CustomGestureView
import org.fcitx.fcitx5.android.utils.pressHighlightDrawable
import splitties.dimensions.dp
import splitties.views.dsl.core.Ui
import splitties.views.dsl.core.add
import splitties.views.dsl.core.lParams
import splitties.views.dsl.core.matchParent
import splitties.views.dsl.core.view
import splitties.views.dsl.core.wrapContent
import splitties.views.gravityCenter

class CandidateItemUi(override val ctx: Context, val theme: Theme) : Ui {

    private val qwenMarker = ImageView(ctx).apply {
        id = R.id.completion_qwen_marker
        setImageResource(R.drawable.ic_completion_sparkle)
        imageTintList = ColorStateList.valueOf(theme.candidateTextColor)
        alpha = 0.55f
        visibility = View.GONE
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    private val text = view(::AutoScaleTextView) {
        scaleMode = AutoScaleTextView.Mode.Proportional
        textSize = AppPrefs.getInstance().keyboard.candidateFontSize.getValue().coerceIn(12, 32).toFloat()
        isSingleLine = true
        gravity = gravityCenter
        setTextColor(theme.candidateTextColor)
    }

    private val content = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = gravityCenter
        isBaselineAligned = false
        addView(qwenMarker, LinearLayout.LayoutParams(ctx.dp(8), ctx.dp(8)).apply {
            marginEnd = ctx.dp(3)
        })
        addView(text, LinearLayout.LayoutParams(wrapContent, matchParent))
    }

    override val root = view(::CustomGestureView) {
        background = pressHighlightDrawable(theme.keyPressHighlightColor)

        /**
         * candidate long press feedback is handled by [org.fcitx.fcitx5.android.input.BaseInputView.showCandidateActionMenu]
         */
        longPressFeedbackEnabled = false

        add(content, lParams(wrapContent, matchParent) {
            gravity = gravityCenter
        })
    }

    fun setQwenCompletion(qwen: Boolean) {
        if (qwenMarker.isVisible == qwen) return
        qwenMarker.isVisible = qwen
        ViewCompat.setStateDescription(root, if (qwen) ctx.getString(R.string.completion_source_qwen) else null)
    }

    fun updateCandidate(candidate: CandidateWord) {
        val fg = theme.candidateTextColor
        val altFg = theme.candidateCommentColor
        text.text = buildSpannedString {
            color(fg) {
                append(candidate.text)
            }
            if (candidate.comment.isNotBlank()) {
                if (candidate.spaceBetweenComment) {
                    append(" ")
                }
                color(altFg) {
                    append(candidate.comment)
                }
            }
        }
    }
}
