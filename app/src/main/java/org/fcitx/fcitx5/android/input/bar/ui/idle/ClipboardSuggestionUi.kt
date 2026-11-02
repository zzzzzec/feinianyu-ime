/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2023 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.bar.ui.idle

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.text.TextUtils
import android.widget.ImageView
import org.fcitx.fcitx5.android.data.clipboard.db.ClipboardEntry
import org.fcitx.fcitx5.android.input.clipboard.ClipboardThumbnail
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.theme.Theme
import org.fcitx.fcitx5.android.input.keyboard.CustomGestureView
import splitties.dimensions.dp
import splitties.resources.drawable
import splitties.views.dsl.constraintlayout.after
import splitties.views.dsl.constraintlayout.before
import splitties.views.dsl.constraintlayout.centerInParent
import splitties.views.dsl.constraintlayout.centerVertically
import splitties.views.dsl.constraintlayout.constraintLayout
import splitties.views.dsl.constraintlayout.endOfParent
import splitties.views.dsl.constraintlayout.lParams
import splitties.views.dsl.constraintlayout.matchConstraints
import splitties.views.dsl.constraintlayout.startOfParent
import splitties.views.dsl.core.Ui
import splitties.views.dsl.core.add
import splitties.views.dsl.core.imageView
import splitties.views.dsl.core.lParams
import splitties.views.dsl.core.matchParent
import splitties.views.dsl.core.textView
import splitties.views.dsl.core.verticalMargin
import splitties.views.dsl.core.wrapContent
import splitties.views.imageDrawable

class ClipboardSuggestionUi(override val ctx: Context, private val theme: Theme) : Ui {

    private val icon = imageView {
        imageDrawable = drawable(R.drawable.ic_clipboard)!!.apply {
            setTint(theme.altKeyTextColor)
        }
    }
    private val thumbnail = ClipboardThumbnail(icon)

    val text = textView {
        isSingleLine = true
        maxWidth = dp(minOf(240, maxOf(80, ctx.resources.configuration.screenWidthDp - 160)))
        ellipsize = TextUtils.TruncateAt.END
        setTextColor(theme.altKeyTextColor)
    }

    fun setEntry(entry: ClipboardEntry, mask: Boolean) {
        thumbnail.clear()
        icon.scaleType = ImageView.ScaleType.CENTER_CROP
        icon.imageDrawable = ctx.drawable(R.drawable.ic_clipboard)!!.apply { setTint(theme.altKeyTextColor) }
        // Only the visual preview is shortened. The entry always retains the complete original payload.
        text.text = if (entry.isImage) ctx.getString(if (mask) R.string.clipboard_image_sensitive else R.string.clipboard_image)
            else if (mask) ClipboardEntry.BULLET.repeat(minOf(12, entry.text.length))
            else entry.text.substring(0, entry.text.offsetByCodePoints(0, minOf(120, entry.text.codePointCount(0, entry.text.length))))
        icon.layoutParams = icon.layoutParams.apply {
            width = ctx.dp(if (entry.isImage) 36 else 20)
            height = ctx.dp(if (entry.isImage) 26 else 20)
        }
        suggestionView.contentDescription = ctx.getString(if (entry.isImage) R.string.clipboard_paste_image else R.string.clipboard_paste_text)
        if (entry.isImage && !mask) thumbnail.load(entry.imageFile!!)
    }

    private val layout = constraintLayout {
        val spacing = dp(8)
        add(icon, lParams(dp(20), dp(20)) {
            startOfParent(spacing)
            before(text)
            centerVertically()
        })
        add(text, lParams(wrapContent, wrapContent) {
            after(icon, spacing)
            endOfParent(spacing)
            centerVertically()
        })
    }

    val suggestionView = CustomGestureView(ctx).apply {
        id = R.id.clipboard_suggestion_chip
        add(layout, lParams(wrapContent, matchParent))
        val shape = GradientDrawable().apply {
            cornerRadius = dp(16).toFloat()
            setColor(theme.keyBackgroundColor)
        }
        background = RippleDrawable(ColorStateList.valueOf(theme.keyPressHighlightColor), shape, null)
        clipToOutline = true
    }

    override val root = constraintLayout {
        add(suggestionView, lParams(wrapContent, matchConstraints) {
            centerInParent()
            verticalMargin = dp(4)
        })
    }
}
