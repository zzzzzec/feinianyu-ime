// SPDX-License-Identifier: GPL-3.0-or-later
package org.fcitx.fcitx5.android.ui.common

import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.AppCompatButton
import androidx.appcompat.widget.SwitchCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updateLayoutParams
import androidx.core.view.updatePadding
import androidx.core.widget.TextViewCompat
import org.fcitx.fcitx5.android.databinding.ActivitySettingsPageBinding
import splitties.resources.drawable
import splitties.resources.styledColor

/** Uses the same toolbar, spacing and theme attributes as the main settings. */
abstract class SettingsPageActivity : AppCompatActivity() {
    protected fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    protected fun settingsContent(@StringRes title: Int, followKeyboard: Boolean = false): LinearLayout {
        enableEdgeToEdge()
        val binding = ActivitySettingsPageBinding.inflate(layoutInflater)
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            binding.root.updateLayoutParams<android.view.ViewGroup.MarginLayoutParams> {
                leftMargin = bars.left; rightMargin = bars.right
            }
            binding.toolbar.updatePadding(top = bars.top)
            val bottom = if (followKeyboard) maxOf(bars.bottom, insets.getInsets(WindowInsetsCompat.Type.ime()).bottom) else bars.bottom
            binding.scroll.updatePadding(bottom = bottom)
            insets
        }
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.apply { setTitle(title); setDisplayHomeAsUpEnabled(true) }
        return binding.content
    }

    protected fun LinearLayout.section(@StringRes title: Int) = TextView(this@SettingsPageActivity).apply {
        setText(title)
        TextViewCompat.setTextAppearance(this, androidx.appcompat.R.style.TextAppearance_AppCompat_Body2)
        setTextColor(styledColor(androidx.appcompat.R.attr.colorAccent))
        setPadding(dp(16), dp(24), dp(16), dp(8))
        addView(this, LinearLayout.LayoutParams(-1, -2))
    }

    protected fun LinearLayout.label(value: String, secondary: Boolean = false) = TextView(this@SettingsPageActivity).apply {
        text = value
        TextViewCompat.setTextAppearance(this, if (secondary) androidx.appcompat.R.style.TextAppearance_AppCompat_Body1 else androidx.appcompat.R.style.TextAppearance_AppCompat_Subhead)
        setTextColor(styledColor(if (secondary) android.R.attr.textColorSecondary else android.R.attr.textColorPrimary))
        setPadding(dp(16), dp(12), dp(16), dp(12))
        addView(this, LinearLayout.LayoutParams(-1, -2))
    }

    protected fun LinearLayout.switchRow(@StringRes title: Int, checked: Boolean, onChange: (Boolean) -> Unit) = SwitchCompat(this@SettingsPageActivity).apply {
        setText(title); textSize = 16f
        minHeight = dp(64); switchPadding = dp(24)
        setPadding(dp(16), dp(12), dp(16), dp(12))
        isChecked = checked
        setOnCheckedChangeListener { _, value -> onChange(value) }
        addView(this, LinearLayout.LayoutParams(-1, -2))
    }

    protected fun LinearLayout.actionRow(@StringRes title: Int, @DrawableRes icon: Int, action: () -> Unit): Button =
        AppCompatButton(this@SettingsPageActivity, null, androidx.appcompat.R.attr.borderlessButtonStyle).apply {
            setText(title); isAllCaps = false; textSize = 16f
            typeface = android.graphics.Typeface.DEFAULT
            gravity = android.view.Gravity.START or android.view.Gravity.CENTER_VERTICAL
            minHeight = dp(56)
            setPadding(dp(16), dp(12), dp(16), dp(12))
            val colors = obtainStyledAttributes(intArrayOf(android.R.attr.textColorPrimary))
            try { setTextColor(colors.getColorStateList(0)) } finally { colors.recycle() }
            setCompoundDrawablesRelativeWithIntrinsicBounds(drawable(icon)?.apply {
                setTint(styledColor(android.R.attr.colorControlNormal))
            }, null, null, null)
            compoundDrawablePadding = dp(32)
            setOnClickListener { action() }
            addView(this, LinearLayout.LayoutParams(-1, -2))
        }

    override fun onSupportNavigateUp(): Boolean { finish(); return true }
}
