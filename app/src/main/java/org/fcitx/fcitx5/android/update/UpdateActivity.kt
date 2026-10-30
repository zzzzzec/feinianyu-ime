// SPDX-License-Identifier: GPL-3.0-or-later
package org.fcitx.fcitx5.android.update

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.enableEdgeToEdge
import androidx.core.content.FileProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.fcitx.fcitx5.android.BuildConfig
import org.fcitx.fcitx5.android.R

class UpdateActivity : AppCompatActivity() {
    private lateinit var status: TextView
    private lateinit var notes: TextView
    private lateinit var install: Button
    private var resumeInstall = false
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        title = getString(R.string.app_updates)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        val padding = (20 * resources.displayMetrics.density).toInt()
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding, padding, padding)
        }
        fun label(text: String, size: Float = 16f) = TextView(this).apply {
            this.text = text; textSize = size; setPadding(0, padding / 2, 0, padding / 2)
            content.addView(this, LinearLayout.LayoutParams(-1, -2))
        }
        label(getString(R.string.app_updates), 22f)
        label(getString(R.string.update_current_version, BuildConfig.VERSION_NAME), 18f)
        val automatic = Switch(this).apply {
            text = getString(R.string.update_automatic)
            isChecked = AppUpdater.automatic(this@UpdateActivity)
            setOnCheckedChangeListener { _, checked -> AppUpdater.setAutomatic(this@UpdateActivity, checked) }
        }
        content.addView(automatic)
        content.addView(Button(this).apply {
            text = getString(R.string.update_check)
            setOnClickListener { lifecycleScope.launch { AppUpdater.check(this@UpdateActivity, manual = true) } }
        })
        status = label("")
        install = Button(this).apply {
            text = getString(R.string.update_install); isEnabled = false
            setOnClickListener { installUpdate() }
        }
        content.addView(install)
        label(getString(R.string.update_changelog), 20f)
        notes = label("")
        val scroll = ScrollView(this).apply { addView(content) }
        ViewCompat.setOnApplyWindowInsetsListener(scroll) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.updatePadding(left = bars.left, top = bars.top, right = bars.right, bottom = bars.bottom)
            insets
        }
        setContentView(scroll)
        lifecycleScope.launch {
            AppUpdater.state.collect { state ->
                status.text = state.message
                notes.text = state.notes.ifEmpty { AppUpdater.latestNotes(this@UpdateActivity) }
                install.isEnabled = state.ready
            }
        }
        lifecycleScope.launch {
            if (AppUpdater.pendingFile(this@UpdateActivity) != null) {
                notes.text = AppUpdater.pendingNotes(this@UpdateActivity)
                status.text = getString(R.string.update_ready, AppUpdater.pendingName(this@UpdateActivity))
                install.isEnabled = true
            } else AppUpdater.check(this@UpdateActivity, manual = false)
        }
    }
    private fun installUpdate() {
        if (Build.VERSION.SDK_INT >= 26 && !packageManager.canRequestPackageInstalls()) {
            resumeInstall = true
            startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")))
            return
        }
        lifecycleScope.launch {
            install.isEnabled = false
            runCatching { withContext(Dispatchers.IO) { AppUpdater.verifyPending(this@UpdateActivity) } }
                .onSuccess { file ->
                    val uri = FileProvider.getUriForFile(this@UpdateActivity, "$packageName.updates", file)
                    startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
                }
                .onFailure {
                    AppUpdater.discardPending(this@UpdateActivity)
                    status.text = getString(R.string.update_failed, it.message)
                }
            install.isEnabled = AppUpdater.pendingFile(this@UpdateActivity) != null
        }
    }
    override fun onResume() {
        super.onResume()
        if (resumeInstall && Build.VERSION.SDK_INT >= 26 && packageManager.canRequestPackageInstalls()) {
            resumeInstall = false; installUpdate()
        }
    }
    override fun onSupportNavigateUp(): Boolean { finish(); return true }
}
