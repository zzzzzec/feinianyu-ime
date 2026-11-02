// SPDX-License-Identifier: GPL-3.0-or-later
package org.fcitx.fcitx5.android.speech

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.ScrollView
import androidx.appcompat.widget.AppCompatImageButton
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.input.bar.ui.ToolButton
import org.fcitx.fcitx5.android.input.dependency.inputMethodService
import org.fcitx.fcitx5.android.input.dependency.theme
import org.fcitx.fcitx5.android.input.wm.InputWindow
import org.fcitx.fcitx5.android.input.wm.InputWindowManager
import org.fcitx.fcitx5.android.input.keyboard.KeyboardWindow
import org.fcitx.fcitx5.android.data.theme.Theme
import org.mechdancer.dependency.manager.must

class SpeechWindow : InputWindow.ExtendedInputWindow<SpeechWindow>() {
    private val service by manager.inputMethodService()
    private val theme by manager.theme()
    private val windowManager: InputWindowManager by manager.must()
    override val showTitle = false
    override val title get() = context.getString(R.string.speech_title)
    private var observer: Job? = null
    private lateinit var panel: SpeechPanelView
    private fun dp(n: Int) = (n * context.resources.displayMetrics.density).toInt()
    override fun onCreateBarExtension(): View = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        addView(ToolButton(context, R.drawable.ic_baseline_arrow_back_24, theme).apply {
            contentDescription = context.getString(R.string.back_to_keyboard)
            setOnClickListener { windowManager.attachWindow(KeyboardWindow) }
        }, LinearLayout.LayoutParams(dp(40), dp(40)))
        addView(TextView(context).apply {
            text = title; textSize = 16f; gravity = Gravity.CENTER_VERTICAL
            setTextColor(theme.altKeyTextColor)
        }, LinearLayout.LayoutParams(0, dp(40), 1f))
        addView(ToolButton(context, R.drawable.ic_baseline_settings_24, theme).apply {
            id = R.id.speech_settings_button
            contentDescription = context.getString(R.string.speech_settings)
            setOnClickListener { SpeechSettings.open(context) }
        }, LinearLayout.LayoutParams(dp(40), dp(40)))
    }
    override fun onCreateView(): View = SpeechPanelView(context, theme) {
        if (service.speechState.value.capturing) service.finishSpeechInput()
        else service.beginSpeechSession()
    }.also { panel = it }
    override fun onAttached() {
        observer = service.lifecycleScope.launch { service.speechState.collect(panel::render) }
        service.beginSpeechSession()
    }
    override fun onDetached() { observer?.cancel(); service.cancelSpeechInput() }
}

/** Keep the transcript readable when the user chooses a short keyboard or a larger system font. */
class SpeechPanelView(context: Context, theme: Theme, onRecord: () -> Unit) : LinearLayout(context) {
    private fun dp(n: Int) = (n * resources.displayMetrics.density).toInt()
    val status = TextView(context).apply {
        id = R.id.speech_listening_status
        textSize = 18f; gravity = Gravity.CENTER; setTextColor(theme.keyTextColor)
        setPadding(0, dp(4), 0, dp(4))
    }
    val transcript = TextView(context).apply {
        id = R.id.speech_transcript
        textSize = 16f; gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
        visibility = View.GONE
        setPadding(dp(4), dp(4), dp(4), dp(4))
        setTextColor(theme.candidateTextColor)
    }
    private val textContent = LinearLayout(context).apply {
        orientation = VERTICAL
        addView(status, LayoutParams(-1, -2))
        addView(transcript, LayoutParams(-1, -2))
    }
    val transcriptScroll = ScrollView(context).apply {
        isFillViewport = true
        addView(textContent, android.widget.FrameLayout.LayoutParams(-1, -2))
    }
    private val wave = SpeechWaveView(context, theme.accentKeyBackgroundColor)
    val microphone = AppCompatImageButton(context).apply {
        id = R.id.speech_record_button
        setImageResource(R.drawable.ic_baseline_keyboard_voice_24)
        imageTintList = ColorStateList.valueOf(theme.accentKeyTextColor)
        background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(theme.accentKeyBackgroundColor) }
        setOnClickListener { onRecord() }
    }
    val controls = LinearLayout(context).apply { gravity = Gravity.CENTER }
    private var expanded: Boolean? = null

    init {
        id = R.id.speech_panel
        orientation = VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        setBackgroundColor(theme.keyboardColor)
        setPadding(dp(16), dp(8), dp(16), dp(8))
        addView(transcriptScroll, LayoutParams(-1, 0, 1f))
        controls.addView(wave)
        controls.addView(microphone)
        addView(controls, LayoutParams(-1, -2))
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val available = MeasureSpec.getSize(heightMeasureSpec) - paddingTop - paddingBottom
        val textLine = transcript.paint.fontMetricsInt.let { it.bottom - it.top }
        val textMinimum = textLine * 2 + transcript.compoundPaddingTop + transcript.compoundPaddingBottom
        val statusReserve = status.lineHeight * 2 + status.compoundPaddingTop + status.compoundPaddingBottom
        val useExpanded = MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.UNSPECIFIED ||
            available >= statusReserve + textMinimum + dp(108)
        if (expanded != useExpanded) {
            expanded = useExpanded
            controls.orientation = if (useExpanded) VERTICAL else HORIZONTAL
            wave.layoutParams = LayoutParams(dp(if (useExpanded) 160 else 96), dp(if (useExpanded) 36 else 24)).apply {
                if (!useExpanded) marginEnd = dp(16)
            }
            val buttonSize = dp(if (useExpanded) 64 else 48)
            microphone.layoutParams = LayoutParams(buttonSize, buttonSize).apply {
                if (useExpanded) { topMargin = dp(4); bottomMargin = dp(4) }
            }
            val inset = dp(if (useExpanded) 20 else 12)
            microphone.setPadding(inset, inset, inset, inset)
        }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }

    fun render(state: SpeechUiState) {
        val label = context.getString(when (state.phase) {
                SpeechPhase.LOADING -> R.string.speech_loading
                SpeechPhase.LISTENING -> R.string.speech_listening
                SpeechPhase.RECOGNIZING -> if (state.capturing) R.string.speech_listening else R.string.speech_recognizing
                SpeechPhase.ERROR -> R.string.speech_failed
                else -> if (state.text.isEmpty()) R.string.speech_no_voice else R.string.speech_continue
            })
        if (status.text.toString() != label) status.text = label
        val text = if (state.phase == SpeechPhase.ERROR) state.error else state.text
        if (transcript.text.toString() != text) {
            val atEnd = transcriptScroll.scrollY + transcriptScroll.height - transcriptScroll.paddingBottom >= textContent.height - dp(8)
            transcript.text = text
            transcript.visibility = if (text.isEmpty()) View.GONE else View.VISIBLE
            if (atEnd) transcriptScroll.post { scrollToLatest() }
        }
        wave.level = state.level
        microphone.isEnabled = state.capturing || state.phase in setOf(SpeechPhase.IDLE, SpeechPhase.FINISHED, SpeechPhase.ERROR)
        microphone.alpha = if (microphone.isEnabled) 1f else 0.4f
        microphone.contentDescription = context.getString(if (state.capturing) R.string.speech_stop else R.string.speech_start)
    }

    /** Follow new text using whole lines, rather than leaving a clipped row at the viewport edge. */
    fun scrollToLatest() {
        val layout = transcript.layout ?: return
        val viewport = transcriptScroll.height
        if (viewport == 0) return
        val bottom = if (transcript.visibility == View.VISIBLE) transcript.bottom else status.bottom
        if (transcript.visibility != View.VISIBLE || bottom <= viewport) {
            transcriptScroll.setPadding(0, 0, 0, 0)
            transcriptScroll.scrollTo(0, 0)
            return
        }
        val textTop = transcript.top + transcript.compoundPaddingTop
        val minimumTop = bottom - viewport
        var first = layout.getLineForVertical((minimumTop - textTop).coerceAtLeast(0))
        while (first < layout.lineCount - 1 && textTop + layout.getLineTop(first) < minimumTop) first++
        val top = textTop + layout.getLineTop(first)
        // Extra space below the last line lets ScrollView align its first visible row exactly.
        transcriptScroll.setPadding(0, 0, 0, (top + viewport - bottom).coerceAtLeast(0))
        transcriptScroll.scrollTo(0, top)
    }
}

private class SpeechWaveView(context: Context, color: Int) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color; strokeCap = Paint.Cap.ROUND }
    var level: Float = 0f
        set(value) { field = value; invalidate() }
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val unit = width / 16f
        paint.strokeWidth = unit * 0.38f
        repeat(12) { index ->
            val shape = 1f - kotlin.math.abs(index - 5.5f) / 8f
            val half = (height * 0.42f * level * shape).coerceAtLeast(paint.strokeWidth / 2)
            val x = width / 2f + (index - 5.5f) * unit
            canvas.drawLine(x, height / 2f - half, x, height / 2f + half, paint)
        }
    }
}
