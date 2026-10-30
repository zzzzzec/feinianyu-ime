// SPDX-License-Identifier: GPL-3.0-or-later
package org.fcitx.fcitx5.android.input.clipboard

import android.content.Context
import android.graphics.Matrix
import android.graphics.drawable.Drawable
import android.widget.ImageView
import splitties.dimensions.dp
import kotlin.math.max
import kotlin.math.roundToInt

/** Fills a masonry column without letterboxing. Tall images show their top. */
class ClipboardImageView(context: Context) : ImageView(context) {
    init { scaleType = ScaleType.MATRIX }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val image = drawable
        val naturalHeight = if (image != null && image.intrinsicWidth > 0)
            (width.toDouble() * image.intrinsicHeight / image.intrinsicWidth).roundToInt() else context.dp(96)
        setMeasuredDimension(width, resolveSize(naturalHeight.coerceIn(context.dp(56), context.dp(160)), heightMeasureSpec))
        updateMatrix()
    }

    override fun setImageDrawable(drawable: Drawable?) {
        super.setImageDrawable(drawable)
        requestLayout()
        updateMatrix()
    }

    private fun updateMatrix() {
        val image = drawable ?: return
        if (image.intrinsicWidth <= 0 || image.intrinsicHeight <= 0 || measuredWidth <= 0 || measuredHeight <= 0) return
        val scale = max(measuredWidth.toFloat() / image.intrinsicWidth, measuredHeight.toFloat() / image.intrinsicHeight)
        imageMatrix = Matrix().apply {
            setScale(scale, scale)
            postTranslate((measuredWidth - image.intrinsicWidth * scale) / 2f, 0f)
        }
    }
}
