// SPDX-License-Identifier: GPL-3.0-or-later
package org.fcitx.fcitx5.android.speech

import android.app.DownloadManager
import android.content.Context
import android.database.Cursor
import android.net.Uri
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.security.MessageDigest

data class SpeechModelFile(val name: String, val size: Long, val sha256: String, val url: String)
data class SpeechDownloadState(val phase: String = "missing", val received: Long = 0, val error: String = "")

object SpeechModelStore {
    const val ID = "sensevoice-small-int8-c71f0ce0-vad5"
    private const val BASE = "https://huggingface.co/csukuangfj/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-2024-07-17/resolve/2365baeacb507f821a0c8120fcee3d484dba7a07"
    val files = listOf(
        SpeechModelFile("model.int8.onnx", 239233841, "c71f0ce00bec95b07744e116345e33d8cbbe08cef896382cf907bf4b51a2cd51", "$BASE/model.int8.onnx"),
        SpeechModelFile("tokens.txt", 315894, "f449eb28dc567533d7fa59be34e2abca8784f771850c78a47fb731a31429a1dc", "$BASE/tokens.txt"),
        SpeechModelFile("silero_vad.onnx", 1807522, "a35ebf52fd3ce5f1469b2a36158dba761bc47b973ea3382b3186ca15b1f5af28",
            "https://huggingface.co/csukuangfj/vad/resolve/fba88cd2e921609e7675c3aaf51e0b9b295da4bc/silero_vad.onnx"))
    val size = files.sumOf { it.size }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private val mutableState = MutableStateFlow(SpeechDownloadState())
    val state = mutableState.asStateFlow()
    private var monitor: Job? = null
    private fun prefs(context: Context) = context.getSharedPreferences("speech_models", Context.MODE_PRIVATE)
    fun directory(context: Context) = File(context.noBackupFilesDir, "speech-models/$ID")
    fun file(context: Context, name: String) = File(directory(context), name)
    private fun verified(context: Context, item: SpeechModelFile): Boolean {
        val target = file(context, item.name)
        return target.isFile && target.length() == item.size &&
            prefs(context).getString("hash_${item.name}", null) == item.sha256 &&
            prefs(context).getLong("mtime_${item.name}", -1) == target.lastModified()
    }
    fun ready(context: Context) = files.all { verified(context, it) }
    @Synchronized fun observe(context: Context) {
        val app = context.applicationContext
        monitor?.cancel()
        monitor = scope.launch {
            do {
                try { refresh(app) } catch (error: Exception) {
                    if (error is CancellationException) throw error
                    mutableState.value = SpeechDownloadState("error", error = error.localizedMessage.orEmpty())
                }
                if (mutableState.value.phase !in setOf("downloading", "verifying")) break
                delay(1000)
            } while (isActive)
        }
    }
    fun download(context: Context) {
        val app = context.applicationContext
        scope.launch {
            try { mutex.withLock {
                val manager = app.getSystemService(DownloadManager::class.java)
                for (item in files) {
                    if (verified(app, item)) continue
                    val key = "download_${item.name}"
                    val old = prefs(app).getLong(key, -1)
                    val exists = if (old < 0) false else manager.query(DownloadManager.Query().setFilterById(old))?.use {
                        it.moveToFirst() && it.int(DownloadManager.COLUMN_STATUS) != DownloadManager.STATUS_FAILED
                    } == true
                    if (exists) continue
                    if (old >= 0) manager.remove(old)
                    val stagingName = "$ID-${item.name}.download"
                    File(app.getExternalFilesDir("speech-downloads"), stagingName).delete()
                    val request = DownloadManager.Request(Uri.parse(item.url)).setTitle("飞念语：中文语音模型")
                        .setAllowedOverRoaming(false)
                        .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                        .setDestinationInExternalFilesDir(app, "speech-downloads", stagingName)
                    if (SpeechSettings.wifiOnly(app)) request.setAllowedNetworkTypes(DownloadManager.Request.NETWORK_WIFI)
                    prefs(app).edit().putLong(key, manager.enqueue(request)).apply()
                }
                mutableState.value = SpeechDownloadState("downloading")
            }; observe(app) } catch (error: Exception) {
                if (error is CancellationException) throw error
                mutableState.value = SpeechDownloadState("error", error = error.localizedMessage.orEmpty())
            }
        }
    }
    fun networkPolicyChanged(context: Context) {
        val app = context.applicationContext
        scope.launch {
            var restart = false
            mutex.withLock {
                val manager = app.getSystemService(DownloadManager::class.java)
                for (item in files) {
                    val key = "download_${item.name}"
                    val id = prefs(app).getLong(key, -1)
                    if (id < 0) continue
                    manager.query(DownloadManager.Query().setFilterById(id))?.use { cursor ->
                        if (cursor.moveToFirst() && cursor.int(DownloadManager.COLUMN_STATUS) != DownloadManager.STATUS_SUCCESSFUL) {
                            manager.remove(id); prefs(app).edit().remove(key).apply(); restart = true
                        }
                    }
                }
            }
            if (restart) download(app)
        }
    }
    suspend fun refresh(context: Context) = withContext(Dispatchers.IO) { mutex.withLock {
        val manager = context.getSystemService(DownloadManager::class.java)
        var received = 0L
        var pending = false
        for (item in files) {
            if (verified(context, item)) { received += item.size; continue }
            val key = "download_${item.name}"
            val id = prefs(context).getLong(key, -1)
            if (id < 0) continue
            manager.query(DownloadManager.Query().setFilterById(id))?.use { cursor ->
                if (!cursor.moveToFirst()) { prefs(context).edit().remove(key).apply(); return@use }
                when (cursor.int(DownloadManager.COLUMN_STATUS)) {
                    DownloadManager.STATUS_SUCCESSFUL -> {
                        mutableState.value = SpeechDownloadState("verifying", received)
                        val source = File(context.getExternalFilesDir("speech-downloads"), "$ID-${item.name}.download")
                        try { installFile(context, source, item) } catch (error: Exception) {
                            manager.remove(id); prefs(context).edit().remove(key).apply(); throw error
                        }
                        manager.remove(id); prefs(context).edit().remove(key).apply(); received += item.size
                    }
                    DownloadManager.STATUS_FAILED -> error("下载失败：${cursor.int(DownloadManager.COLUMN_REASON)}")
                    else -> { pending = true; received += cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)).coerceAtLeast(0) }
                }
            }
        }
        mutableState.value = SpeechDownloadState(when { ready(context) -> "ready"; pending -> "downloading"; else -> "missing" }, received)
    } }
    /** Each file is pinned and atomically installed; an APK upgrade doesn't touch this directory. */
    fun installFile(context: Context, source: File, item: SpeechModelFile) {
        require(source.length() == item.size) { "模型文件大小不符：${item.name}" }
        val target = file(context, item.name).apply { parentFile!!.mkdirs() }
        val temporary = File(target.parentFile, "${item.name}.tmp")
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            source.inputStream().buffered().use { input -> temporary.outputStream().buffered().use { output ->
                val buffer = ByteArray(256 * 1024)
                while (true) { val n = input.read(buffer); if (n < 0) break; digest.update(buffer, 0, n); output.write(buffer, 0, n) }
            } }
            require(digest.digest().joinToString("") { "%02x".format(it) } == item.sha256) { "模型校验失败：${item.name}" }
            check(temporary.renameTo(target)) { "无法保存模型" }
            check(prefs(context).edit().putString("hash_${item.name}", item.sha256)
                .putLong("mtime_${item.name}", target.lastModified()).commit()) { "无法保存模型校验记录" }
        } finally { temporary.delete() }
    }
    private fun Cursor.int(key: String) = getInt(getColumnIndexOrThrow(key))
}
