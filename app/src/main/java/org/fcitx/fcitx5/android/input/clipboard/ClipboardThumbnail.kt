// SPDX-License-Identifier: GPL-3.0-or-later
package org.fcitx.fcitx5.android.input.clipboard

import android.widget.ImageView
import kotlinx.coroutines.*
import org.fcitx.fcitx5.android.data.clipboard.ClipboardImages

class ClipboardThumbnail(private val view: ImageView) {
    private var job: Job? = null
    companion object { private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate) }
    fun clear() { job?.cancel(); job = null; view.setImageDrawable(null) }
    fun load(file: String, unavailable: () -> Unit = {}) {
        clear()
        job = scope.launch {
            val bitmap = try { ClipboardImages.store.thumbnail(file) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { null }
            if (bitmap == null) unavailable() else view.setImageBitmap(bitmap)
        }
    }
}
