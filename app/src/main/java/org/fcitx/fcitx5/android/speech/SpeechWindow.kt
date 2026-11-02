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
import org.mechdancer.dependency.manager.must

class SpeechWindow : InputWindow.ExtendedInputWindow<SpeechWindow>() {
    private val service by manager.inputMethodService()
    private val theme by manager.theme()
    private val windowManager: InputWindowManager by manager.must()
    override val showTitle = false
    override val title get() = context.getString(R.string.speech_title)
    private var observer: Job? = null
    private lateinit var status: TextView
    private lateinit var transcript: TextView
    private lateinit var microphone: AppCompatImageButton
    private lateinit var wave: SpeechWaveView
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
    override fun onCreateView(): View = LinearLayout(context).apply {
        id = R.id.speech_panel
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        setBackgroundColor(theme.keyboardColor)
        setPadding(dp(24), dp(12), dp(24), dp(12))
        status = TextView(context).apply {
            id = R.id.speech_listening_status
            textSize = 18f; gravity = Gravity.CENTER; setTextColor(theme.keyTextColor)
        }
        addView(status, LinearLayout.LayoutParams(-1, dp(36)))
        transcript = TextView(context).apply {
            id = R.id.speech_transcript
            textSize = 16f; gravity = Gravity.CENTER; maxLines = 3
            setTextColor(theme.candidateTextColor)
        }
        addView(transcript, LinearLayout.LayoutParams(-1, 0, 1f))
        wave = SpeechWaveView(context, theme.accentKeyBackgroundColor)
        addView(wave, LinearLayout.LayoutParams(dp(160), dp(36)))
        microphone = AppCompatImageButton(context).apply {
            id = R.id.speech_record_button
            setImageResource(R.drawable.ic_baseline_keyboard_voice_24)
            imageTintList = ColorStateList.valueOf(theme.accentKeyTextColor)
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(theme.accentKeyBackgroundColor) }
            setPadding(dp(20), dp(20), dp(20), dp(20))
            setOnClickListener {
                if (service.speechState.value.capturing) service.finishSpeechInput()
                else service.beginSpeechSession()
            }
        }
        addView(microphone, LinearLayout.LayoutParams(dp(64), dp(64)).apply { topMargin = dp(4); bottomMargin = dp(4) })
    }
    override fun onAttached() {
        observer = service.lifecycleScope.launch { service.speechState.collect { state ->
            status.text = context.getString(when (state.phase) {
                SpeechPhase.LOADING -> R.string.speech_loading
                SpeechPhase.LISTENING -> R.string.speech_listening
                SpeechPhase.RECOGNIZING -> if (state.capturing) R.string.speech_listening else R.string.speech_recognizing
                SpeechPhase.ERROR -> R.string.speech_failed
                else -> if (state.text.isEmpty()) R.string.speech_no_voice else R.string.speech_continue
            })
            transcript.text = if (state.phase == SpeechPhase.ERROR) state.error else state.text
            wave.level = state.level
            microphone.isEnabled = state.capturing || state.phase in setOf(SpeechPhase.IDLE, SpeechPhase.FINISHED, SpeechPhase.ERROR)
            microphone.alpha = if (microphone.isEnabled) 1f else 0.4f
            microphone.contentDescription = context.getString(if (state.capturing) R.string.speech_stop else R.string.speech_start)
        } }
        service.beginSpeechSession()
    }
    override fun onDetached() { observer?.cancel(); service.cancelSpeechInput() }
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
