/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2023 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.clipboard

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.text.TextUtils
import android.view.View
import android.view.Gravity
import android.widget.FrameLayout
import org.fcitx.fcitx5.android.data.clipboard.db.ClipboardEntry
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.theme.Theme
import org.fcitx.fcitx5.android.input.keyboard.CustomGestureView
import splitties.dimensions.dp
import splitties.resources.drawable
import splitties.views.dsl.core.Ui
import splitties.views.dsl.core.add
import splitties.views.dsl.core.imageView
import splitties.views.dsl.core.lParams
import splitties.views.dsl.core.matchParent
import splitties.views.dsl.core.textView
import splitties.views.dsl.core.wrapContent
import splitties.views.imageDrawable
import splitties.views.setPaddingDp

class ClipboardEntryUi(override val ctx: Context, private val theme: Theme, radius: Float) : Ui {

    private val image = ClipboardImageView(ctx).apply {
        contentDescription = ctx.getString(R.string.clipboard_image)
        visibility = View.GONE
    }
    private val thumbnail = ClipboardThumbnail(image)

    val textView = textView {
        minLines = 1
        maxLines = 4
        textSize = 14f
        setPaddingDp(8, 4, 8, 4)
        ellipsize = TextUtils.TruncateAt.END
        setTextColor(theme.keyTextColor)
    }

    val pin = imageView {
        imageDrawable = drawable(R.drawable.ic_baseline_push_pin_24)!!.apply {
            setTint(theme.altKeyTextColor)
            setAlpha(0.3f)
        }
    }

    val layout = FrameLayout(ctx).apply {
        addView(image, FrameLayout.LayoutParams(matchParent, wrapContent))
        addView(textView, FrameLayout.LayoutParams(matchParent, wrapContent))
        addView(pin, FrameLayout.LayoutParams(dp(12), dp(12), Gravity.BOTTOM or Gravity.END).apply {
            bottomMargin = dp(2); marginEnd = dp(2)
        })
    }

    override val root = CustomGestureView(ctx).apply {
        clipToOutline = true
        isClickable = true
        minimumHeight = dp(30)
        foreground = RippleDrawable(
            ColorStateList.valueOf(theme.keyPressHighlightColor), null,
            GradientDrawable().apply {
                cornerRadius = radius
                setColor(Color.WHITE)
            }
        )
        background = GradientDrawable().apply {
            cornerRadius = radius
            setColor(theme.clipboardEntryColor)
        }
        add(layout, lParams(matchParent, wrapContent))
    }

    fun setEntry(text: String, pinned: Boolean) {
        textView.text = text
        pin.visibility = if (pinned) View.VISIBLE else View.GONE
    }

    fun bind(entry: ClipboardEntry, maskSensitive: Boolean) {
        thumbnail.clear()
        pin.visibility = if (entry.pinned) View.VISIBLE else View.GONE
        root.minimumHeight = ctx.dp(30)
        image.visibility = if (entry.isImage && !maskSensitive) View.VISIBLE else View.GONE
        textView.visibility = if (!entry.isImage || maskSensitive) View.VISIBLE else View.GONE
        root.contentDescription = if (entry.isImage) ctx.getString(if (maskSensitive) R.string.clipboard_image_sensitive else R.string.clipboard_image) else null
        if (entry.isImage) {
            if (maskSensitive) textView.setText(R.string.clipboard_image_sensitive)
            else thumbnail.load(entry.imageFile!!) {
                image.visibility = View.GONE
                textView.visibility = View.VISIBLE
                textView.setText(R.string.clipboard_image_missing)
            }
        } else textView.text = ClipboardAdapter.excerptText(entry.text, maskSensitive)
    }

    fun recycle() = thumbnail.clear()
}
