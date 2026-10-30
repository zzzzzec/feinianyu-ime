// SPDX-License-Identifier: GPL-3.0-or-later
package org.fcitx.fcitx5.android.completion

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

data class ModelDownloadState(val phase: String = "missing", val received: Long = 0, val error: String = "")

object QwenModelStore {
    const val ID = "qwen35-08b-base-q4km-99fe51dc"
    const val SHA256 = "99fe51dc14029f8fb69c9c90a8c59ce733488df22a2de09f4e69203bf1e9fd93"
    const val SIZE = 529297536L
    const val FILE_NAME = "Qwen3.5-0.8B-Base.Q4_K_M.gguf"
    const val URL = "https://huggingface.co/mradermacher/Qwen3.5-0.8B-Base-GGUF/resolve/d98ffedc21f95bcd34909d356dffa47cffcd0066/Qwen3.5-0.8B-Base.Q4_K_M.gguf?download=true"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private val mutableState = MutableStateFlow(ModelDownloadState())
    val state = mutableState.asStateFlow()
    private var monitor: Job? = null
    private fun prefs(context: Context) = context.getSharedPreferences("completion_model", Context.MODE_PRIVATE)
    fun file(context: Context) = File(File(context.noBackupFilesDir, "completion-models"), "$ID.gguf")
    fun readyFile(context: Context): File? = file(context).takeIf {
        it.isFile && it.length() == SIZE && prefs(context).getString("verified_id", null) == ID &&
            prefs(context).getLong("verified_mtime", -1) == it.lastModified()
    }
    fun observe(context: Context) {
        val app = context.applicationContext
        if (monitor?.isActive == true) return
        monitor = scope.launch {
            while (isActive) {
                runCatching { refresh(app) }.onFailure {
                    mutableState.value = ModelDownloadState("error", error = it.message.orEmpty())
                }
                if (mutableState.value.phase !in setOf("downloading", "verifying")) break
                delay(1500)
            }
        }
    }
    /** System DownloadManager continues across process restarts and APK upgrades. Wi-Fi only. */
    fun download(context: Context) {
        val app = context.applicationContext
        scope.launch { try { mutex.withLock {
            if (readyFile(app) != null) { mutableState.value = ModelDownloadState("ready", SIZE); return@withLock }
            val manager = app.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            val previous = prefs(app).getLong("download_id", -1)
            if (previous != -1L) {
                manager.query(DownloadManager.Query().setFilterById(previous))?.use { cursor ->
                    if (cursor.moveToFirst() && cursor.int(DownloadManager.COLUMN_STATUS) in setOf(
                        DownloadManager.STATUS_PENDING, DownloadManager.STATUS_RUNNING, DownloadManager.STATUS_PAUSED,
                        DownloadManager.STATUS_SUCCESSFUL)) { observe(app); return@withLock }
                }
                manager.remove(previous)
            }
            val staging = File(app.getExternalFilesDir("completion-downloads"), "$ID.download")
            staging.delete()
            val request = DownloadManager.Request(Uri.parse(URL)).setTitle("飞念语：Qwen 中文补全模型")
                .setAllowedNetworkTypes(DownloadManager.Request.NETWORK_WIFI).setAllowedOverRoaming(false)
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setDestinationInExternalFilesDir(app, "completion-downloads", "$ID.download")
            val id = manager.enqueue(request)
            prefs(app).edit().putLong("download_id", id).apply()
            mutableState.value = ModelDownloadState("downloading")
        }; observe(app) } catch (error: Exception) {
            mutableState.value = ModelDownloadState("error", error = error.localizedMessage.orEmpty())
        } }
    }
    suspend fun refresh(context: Context) = withContext(Dispatchers.IO) { mutex.withLock {
        if (readyFile(context) != null) { mutableState.value = ModelDownloadState("ready", SIZE); return@withLock }
        val id = prefs(context).getLong("download_id", -1)
        if (id == -1L) { mutableState.value = ModelDownloadState(); return@withLock }
        val manager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        manager.query(DownloadManager.Query().setFilterById(id))?.use { cursor ->
            if (!cursor.moveToFirst()) { mutableState.value = ModelDownloadState(); return@withLock }
            when (cursor.int(DownloadManager.COLUMN_STATUS)) {
                DownloadManager.STATUS_SUCCESSFUL -> {
                    mutableState.value = ModelDownloadState("verifying", SIZE)
                    val staging = File(context.getExternalFilesDir("completion-downloads"), "$ID.download")
                    installVerified(context, staging)
                    manager.remove(id)
                    prefs(context).edit().remove("download_id").apply()
                }
                DownloadManager.STATUS_FAILED -> mutableState.value = ModelDownloadState("error", error = "下载失败：${cursor.int(DownloadManager.COLUMN_REASON)}")
                else -> mutableState.value = ModelDownloadState("downloading", cursor.long(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
            }
        }
    } }
    /** Also used for offline file provisioning in device tests; identical size/hash verification. */
    fun installVerified(context: Context, source: File) {
        require(source.length() == SIZE) { "模型文件大小不符" }
        val target = file(context).apply { parentFile!!.mkdirs() }
        val temporary = File(target.parentFile, "$ID.tmp")
        val digest = MessageDigest.getInstance("SHA-256")
        try {
            source.inputStream().buffered().use { input -> temporary.outputStream().buffered().use { output ->
                val buffer = ByteArray(256 * 1024)
                while (true) { val count = input.read(buffer); if (count < 0) break; digest.update(buffer, 0, count); output.write(buffer, 0, count) }
            } }
            require(digest.digest().joinToString("") { "%02x".format(it) } == SHA256) { "模型校验失败，请重新下载" }
            check(temporary.renameTo(target)) { "无法保存模型" }
            prefs(context).edit().putString("verified_id", ID).putLong("verified_mtime", target.lastModified()).apply()
            mutableState.value = ModelDownloadState("ready", SIZE)
        } finally { temporary.delete() }
    }
    private fun Cursor.int(name: String) = getInt(getColumnIndexOrThrow(name))
    private fun Cursor.long(name: String) = getLong(getColumnIndexOrThrow(name))
}
