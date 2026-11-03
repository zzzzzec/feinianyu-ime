// SPDX-License-Identifier: GPL-3.0-or-later
package org.fcitx.fcitx5.android

import android.app.Activity
import android.os.Bundle
import android.view.Gravity
import android.view.WindowManager
import android.widget.TextView

/** Keeps instrumented native workloads out of vendor background-app freezers. */
class QwenBenchmarkActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(TextView(this).apply {
            text = "正在测试智能续写性能…"
            textSize = 20f
            gravity = Gravity.CENTER
        })
    }
}
