/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2023 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.bar.ui.idle

import android.content.Context
import android.text.TextUtils
import android.widget.ImageView
import org.fcitx.fcitx5.android.data.clipboard.db.ClipboardEntry
import org.fcitx.fcitx5.android.input.clipboard.ClipboardThumbnail
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.theme.Theme
import org.fcitx.fcitx5.android.input.keyboard.CustomGestureView
import org.fcitx.fcitx5.android.utils.rippleDrawable
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
        maxWidth = dp(120)
        ellipsize = TextUtils.TruncateAt.END
        setTextColor(theme.altKeyTextColor)
    }

    fun setEntry(entry: ClipboardEntry, mask: Boolean) {
        thumbnail.clear()
        icon.scaleType = ImageView.ScaleType.CENTER_CROP
        icon.imageDrawable = ctx.drawable(R.drawable.ic_clipboard)!!.apply { setTint(theme.altKeyTextColor) }
        text.text = if (entry.isImage) ctx.getString(if (mask) R.string.clipboard_image_sensitive else R.string.clipboard_image)
            else if (mask) ClipboardEntry.BULLET.repeat(minOf(42, entry.text.length)) else entry.text.take(42)
        if (entry.isImage && !mask) thumbnail.load(entry.imageFile!!)
    }

    private val layout = constraintLayout {
        val spacing = dp(4)
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
        add(layout, lParams(wrapContent, matchParent))
        background = rippleDrawable(theme.keyPressHighlightColor)
    }

    override val root = constraintLayout {
        add(suggestionView, lParams(wrapContent, matchConstraints) {
            centerInParent()
            verticalMargin = dp(4)
        })
    }
}
