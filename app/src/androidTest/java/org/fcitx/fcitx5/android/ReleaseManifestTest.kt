// SPDX-License-Identifier: GPL-3.0-or-later
package org.fcitx.fcitx5.android

import org.fcitx.fcitx5.android.update.ReleaseManifest
import org.junit.Assert.*
import org.junit.Test

class ReleaseManifestTest {
    private fun manifest(packageName: String = "dev.feinianyu.ime", name: String = "feinianyu-ime.apk", size: Long = 100) = """
        {"schemaVersion":1,"applicationId":"$packageName","assets":[
          {"name":"$name","abi":"arm64-v8a","versionCode":202,"size":$size,"sha256":"${"a".repeat(64)}"},
          {"name":"feinianyu-ime-x86_64.apk","abi":"x86_64","versionCode":204,"size":100,"sha256":"${"b".repeat(64)}"}
        ]}
    """.trimIndent()
    @Test fun selectsCompatibleNewerApk() {
        val asset = ReleaseManifest.select(manifest(), "dev.feinianyu.ime", listOf("arm64-v8a", "armeabi-v7a"), 192)
        assertEquals("arm64-v8a", asset?.abi)
        assertEquals(202L, asset?.versionCode)
        assertEquals(204L, ReleaseManifest.select(manifest(), "dev.feinianyu.ime", listOf("x86_64"), 194)?.versionCode)
    }
    @Test fun doesNotInstallSameOrOlderVersions() {
        assertNull(ReleaseManifest.select(manifest(), "dev.feinianyu.ime", listOf("arm64-v8a"), 202))
        assertNull(ReleaseManifest.select(manifest(), "dev.feinianyu.ime", listOf("armeabi-v7a"), 192))
    }
    @Test fun rejectsWrongPackagePathsAndUnboundedSizes() {
        for (json in listOf(manifest(packageName = "other.app"), manifest(name = "../evil.apk"), manifest(size = 314572801))) {
            assertTrue(runCatching { ReleaseManifest.select(json, "dev.feinianyu.ime", listOf("arm64-v8a"), 192) }.isFailure)
        }
    }
}
