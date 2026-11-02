// SPDX-License-Identifier: GPL-3.0-or-later
package org.fcitx.fcitx5.android

import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.test.platform.app.InstrumentationRegistry
import org.fcitx.fcitx5.android.data.theme.ThemePreset
import org.fcitx.fcitx5.android.speech.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class SpeechLayoutTest {
    @Test fun shortKeyboardsAndLargeFontsKeepTranscriptReadable() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext
        instrumentation.runOnMainSync {
            for (scale in listOf(1f, 1.5f, 2f)) for (height in listOf(120, 160, 240))
                for (theme in listOf(ThemePreset.MaterialDark, ThemePreset.GboardStage1Light))
                    for (text in listOf("你好", "语音识别出来的完整文字需要能够看清，也应该可以滚动查看。".repeat(8))) {
                    val context = app.createConfigurationContext(Configuration(app.resources.configuration).apply { fontScale = scale })
                    fun dp(value: Int) = (value * context.resources.displayMetrics.density).toInt()
                    val panel = SpeechPanelView(context, theme) {}
                    panel.render(SpeechUiState(SpeechPhase.FINISHED, text))
                    panel.measure(View.MeasureSpec.makeMeasureSpec(dp(360), View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(dp(height), View.MeasureSpec.EXACTLY))
                    panel.layout(0, 0, panel.measuredWidth, panel.measuredHeight)
                    val description = "height=$height fontScale=$scale theme=${theme.name}"
                    val lineHeight = panel.transcript.layout.getLineBottom(0) + panel.transcript.compoundPaddingTop
                    assertTrue("$description must show a complete line", panel.transcriptScroll.height >= lineHeight)
                    assertTrue("$description keeps controls inside panel", panel.microphone.height > 0 && panel.controls.bottom <= panel.height)
                    if (text.length > 100) assertTrue("Long text must not be truncated at three lines", panel.transcript.layout.lineCount > 3)
                    assertEquals(text, panel.transcript.text.toString())
                    panel.transcriptScroll.isSmoothScrollingEnabled = false
                    panel.scrollToLatest()
                    val visibleHeight = panel.transcriptScroll.height - panel.transcriptScroll.paddingBottom
                    assertTrue("$description can reach the last line", panel.transcript.bottom - panel.transcriptScroll.scrollY <= visibleHeight)
                    val last = panel.transcript.layout.lineCount - 1
                    val lineTop = panel.transcript.top + panel.transcript.compoundPaddingTop + panel.transcript.layout.getLineTop(last)
                    val lineBottom = panel.transcript.top + panel.transcript.compoundPaddingTop + panel.transcript.layout.getLineBottom(last)
                    assertTrue("$description must not cut the last line's top", lineTop >= panel.transcriptScroll.scrollY)
                    assertTrue("$description must not cut the last line's bottom", lineBottom <= panel.transcriptScroll.scrollY + visibleHeight)
                    val first = panel.transcript.layout.getLineForVertical((panel.transcriptScroll.scrollY - panel.transcript.top - panel.transcript.compoundPaddingTop).coerceAtLeast(0))
                    val firstTop = panel.transcript.top + panel.transcript.compoundPaddingTop + panel.transcript.layout.getLineTop(first)
                    assertTrue("$description must not leave a partial first row", firstTop >= panel.transcriptScroll.scrollY)
                    panel.transcriptScroll.scrollTo(0, 0)
                    panel.render(SpeechUiState(SpeechPhase.FINISHED, text, level = 0.5f))
                    assertEquals("Audio level updates must not reset reading position", 0, panel.transcriptScroll.scrollY)
                    if (theme == ThemePreset.MaterialDark && (height == 160 && scale == 1f || height == 120 && scale == 2f)) {
                        panel.scrollToLatest()
                        val bitmap = Bitmap.createBitmap(panel.width, panel.height, Bitmap.Config.ARGB_8888)
                        panel.draw(Canvas(bitmap))
                        File(app.filesDir, "speech-layout-${height}-${scale}.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                        bitmap.recycle()
                    }
                }
        }
    }
}
