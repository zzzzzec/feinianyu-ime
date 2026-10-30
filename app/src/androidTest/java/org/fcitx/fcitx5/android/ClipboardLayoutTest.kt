// SPDX-License-Identifier: GPL-3.0-or-later
package org.fcitx.fcitx5.android

import android.graphics.Bitmap
import android.graphics.RectF
import android.view.View
import androidx.test.platform.app.InstrumentationRegistry
import org.fcitx.fcitx5.android.data.clipboard.db.ClipboardEntry
import org.fcitx.fcitx5.android.data.theme.ThemePreset
import org.fcitx.fcitx5.android.input.clipboard.ClipboardEntryUi
import org.fcitx.fcitx5.android.input.clipboard.ClipboardImageView
import org.junit.Assert.*
import org.junit.Test
import splitties.dimensions.dp

class ClipboardLayoutTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private fun measure(view: View, width: Int) {
        view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        view.layout(0, 0, view.measuredWidth, view.measuredHeight)
    }

    @Test fun wideSquareAndTallImagesFillTheirCardsWithoutStretchingOrLetterboxing() = instrumentation.runOnMainSync {
        val view = ClipboardImageView(context)
        val width = context.dp(160)
        for ((w, h) in listOf(400 to 200, 200 to 200, 100 to 400, 400 to 40)) {
            val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            view.setImageBitmap(bitmap)
            measure(view, width)
            assertTrue(view.measuredHeight in context.dp(56)..context.dp(160))
            if (w == 400 && h == 200) assertEquals(width / 2, view.measuredHeight)
            val bounds = RectF(0f, 0f, w.toFloat(), h.toFloat())
            view.imageMatrix.mapRect(bounds)
            assertTrue(bounds.left <= .1f && bounds.right >= view.measuredWidth - .1f)
            assertEquals(0f, bounds.top, .1f)
            assertTrue(bounds.bottom >= view.measuredHeight - .1f)
            assertEquals(bounds.width() / w, bounds.height() / h, .001f)
            view.setImageDrawable(null)
            bitmap.recycle()
        }
    }

    @Test fun recycledImageCardShrinksForTextAndMaskedImages() = instrumentation.runOnMainSync {
        val ui = ClipboardEntryUi(context, ThemePreset.GboardStage1Light, context.dp(4).toFloat())
        val width = context.dp(160)
        val imageEntry = ClipboardEntry(text = "", imageFile = "unavailable.png", type = "image/png")
        ui.bind(imageEntry, false)
        val bitmap = Bitmap.createBitmap(100, 400, Bitmap.Config.ARGB_8888)
        (ui.layout.getChildAt(0) as ClipboardImageView).setImageBitmap(bitmap)
        measure(ui.root, width)
        val imageHeight = ui.root.measuredHeight
        assertEquals(context.dp(160), imageHeight)
        ui.bind(ClipboardEntry(text = "Short clip"), false)
        measure(ui.root, width)
        assertTrue(ui.root.measuredHeight < imageHeight / 2)
        ui.bind(imageEntry, true)
        measure(ui.root, width)
        assertTrue(ui.root.measuredHeight < imageHeight / 2)
        ui.recycle()
        bitmap.recycle()
    }
}
