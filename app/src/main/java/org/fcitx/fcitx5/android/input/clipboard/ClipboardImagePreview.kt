// SPDX-License-Identifier: GPL-3.0-or-later
package org.fcitx.fcitx5.android.input.clipboard

import android.app.AlertDialog
import android.content.Context
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.ScrollView
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.clipboard.ClipboardImages
import org.fcitx.fcitx5.android.data.clipboard.db.ClipboardEntry
import org.fcitx.fcitx5.android.input.FcitxInputMethodService
import org.fcitx.fcitx5.android.utils.toast
import splitties.dimensions.dp

object ClipboardImagePreview {
    fun show(service: FcitxInputMethodService, context: Context, entry: ClipboardEntry) {
        val file = entry.imageFile ?: return
        val metrics = context.resources.displayMetrics
        val heightLimit = minOf((metrics.heightPixels * .5f).toInt(), context.dp(400))
        val image = ImageView(context).apply {
            adjustViewBounds = true
            scaleType = ImageView.ScaleType.FIT_CENTER
            contentDescription = context.getString(R.string.clipboard_image)
        }
        val scroll = object : ScrollView(context) {
            override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                val available = if (MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.UNSPECIFIED) heightLimit
                    else minOf(heightLimit, MeasureSpec.getSize(heightMeasureSpec))
                super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(available, MeasureSpec.AT_MOST))
            }
        }.apply {
            addView(image, FrameLayout.LayoutParams(-1, -2))
        }
        val progress = ProgressBar(context)
        val content = FrameLayout(context).apply {
            minimumHeight = context.dp(56)
            addView(scroll, FrameLayout.LayoutParams(-1, -2))
            addView(progress, FrameLayout.LayoutParams(context.dp(32), context.dp(32), Gravity.CENTER))
        }
        val dialog = AlertDialog.Builder(context)
            .setTitle(R.string.clipboard_image_preview)
            .setPositiveButton(R.string.clipboard_image_close, null)
            .create()
        dialog.setView(content, 0, 0, 0, 0)
        service.showDialog(dialog)
        service.lifecycleScope.launch {
            val bitmap = try { ClipboardImages.store.preview(file, metrics.widthPixels) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { null }
            if (!dialog.isShowing) return@launch
            progress.visibility = View.GONE
            if (bitmap == null) { dialog.dismiss(); context.toast(R.string.clipboard_image_read_failed) }
            else image.setImageBitmap(bitmap)
        }
    }
}
