// SPDX-License-Identifier: GPL-3.0-or-later
package org.fcitx.fcitx5.android.core

import android.content.Context
import java.io.File
import java.security.MessageDigest

/** Call on an IO dispatcher. Model files live outside Android cloud backup. */
object SuzuAssets {
    const val MODEL = "suzu-2.4.q8_0.gguf"
    const val VOCAB = "suzu-2.4.q8_0.gguf.vocab.txt"
    private val hashes = mapOf(
        MODEL to "a2ff2c46110c7ba47746871eaf8ff37cc4fab58105ddbe11e0a768e340cdb996",
        VOCAB to "21f87a1ce1e99597342ed521787a1d7cac308edb6d2eddeb6c2db06c05668e5d"
    )

    private fun digest(file: File): String {
        val sha = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(65536)
            while (true) {
                val size = input.read(buffer)
                if (size < 0) break
                sha.update(buffer, 0, size)
            }
        }
        return sha.digest().joinToString("") { "%02x".format(it) }
    }

    @Synchronized
    fun prepare(context: Context): Pair<String, String> {
        val directory = File(context.noBackupFilesDir, "suzu-2.4").apply { mkdirs() }
        hashes.forEach { (name, hash) ->
            val target = File(directory, name)
            if (!target.isFile || digest(target) != hash) {
                val temporary = File(directory, "$name.tmp")
                context.assets.open("suzu/$name").use { input ->
                    temporary.outputStream().use { input.copyTo(it) }
                }
                check(digest(temporary) == hash) { "Suzu file checksum mismatch: $name" }
                check(temporary.renameTo(target)) { "Cannot install Suzu asset: $name" }
            }
        }
        return File(directory, MODEL).absolutePath to File(directory, VOCAB).absolutePath
    }
}
