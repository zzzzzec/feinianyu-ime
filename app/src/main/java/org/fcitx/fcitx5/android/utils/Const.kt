/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2023 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.utils

import org.fcitx.fcitx5.android.BuildConfig

object Const {
    const val versionName = "${BuildConfig.VERSION_NAME}-${BuildConfig.BUILD_TYPE}"
    const val githubRepo = "https://github.com/zzzzzec/feinianyu-ime"
    const val licenseSpdxId = "GPL-3.0-only"
    const val licenseUrl = "https://www.gnu.org/licenses/gpl-3.0.html"
    const val privacyPolicyUrl = "$githubRepo/blob/master/docs/privacy.md"
    const val faqUrl = "https://fcitx5-android.github.io/faq/"
}
