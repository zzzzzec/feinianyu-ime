// SPDX-License-Identifier: GPL-3.0-or-later
package org.fcitx.fcitx5.android.update

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.job.JobInfo
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import org.fcitx.fcitx5.android.BuildConfig
import org.fcitx.fcitx5.android.R
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

data class UpdateState(val message: String = "", val notes: String = "", val ready: Boolean = false)

object AppUpdater {
    private const val INTERVAL = 12 * 60 * 60 * 1000L
    private const val CHANNEL = "app_updates"
    private const val NOTIFICATION = 2401
    private val mutex = Mutex()
    private val mutableState = MutableStateFlow(UpdateState())
    val state = mutableState.asStateFlow()
    private fun preferences(context: Context) = context.getSharedPreferences("app_updates", Context.MODE_PRIVATE)
    fun automatic(context: Context) = preferences(context).getBoolean("automatic", true)
    fun setAutomatic(context: Context, enabled: Boolean) {
        preferences(context).edit().putBoolean("automatic", enabled).apply()
        schedule(context)
    }
    fun schedule(context: Context) {
        val prefs = preferences(context)
        val installedUpdate = prefs.getLong("ready_version", 0)
        if (installedUpdate > 0 && installedUpdate <= version(context)) {
            File(directory(context), "update-$installedUpdate.apk").delete()
            prefs.edit().remove("ready_version").remove("ready_name").remove("ready_notes").remove("ready_sha256").apply()
        }
        val scheduler = context.getSystemService(Context.JOB_SCHEDULER_SERVICE) as android.app.job.JobScheduler
        if (!automatic(context)) { scheduler.cancel(NOTIFICATION); return }
        scheduler.schedule(JobInfo.Builder(NOTIFICATION, ComponentName(context, UpdateJobService::class.java))
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_UNMETERED).setPeriodic(INTERVAL).setPersisted(true).build())
    }
    fun version(context: Context): Long {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        return if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else @Suppress("DEPRECATION") info.versionCode.toLong()
    }
    private fun directory(context: Context) = File(context.cacheDir, "updates").apply { mkdirs() }
    fun pendingFile(context: Context): File? {
        val prefs = preferences(context)
        val expectedVersion = prefs.getLong("ready_version", 0)
        if (expectedVersion <= version(context)) return null
        return File(directory(context), "update-$expectedVersion.apk").takeIf { it.isFile }
    }
    fun pendingNotes(context: Context) = preferences(context).getString("ready_notes", "").orEmpty()
    fun pendingName(context: Context) = preferences(context).getString("ready_name", "").orEmpty()
    fun discardPending(context: Context) {
        val prefs = preferences(context)
        val code = prefs.getLong("ready_version", 0)
        if (code > 0) File(directory(context), "update-$code.apk").delete()
        prefs.edit().remove("ready_version").remove("ready_name").remove("ready_notes").remove("ready_sha256").apply()
        (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).cancel(NOTIFICATION)
    }

    // Only GitHub and its release-asset CDN may serve an update. Redirects are
    // bounded and always HTTPS. No personal text or app data is sent.
    private fun connection(address: String): HttpURLConnection {
        var target = URL(address)
        repeat(6) {
            require(target.protocol == "https" && target.host in setOf("api.github.com", "github.com", "release-assets.githubusercontent.com", "objects.githubusercontent.com")) { "Unsupported update download host" }
            val connection = (target.openConnection() as HttpURLConnection).apply {
                connectTimeout = 15000; readTimeout = 45000
                instanceFollowRedirects = false
                setRequestProperty("User-Agent", "FeinianyuIME/${BuildConfig.VERSION_NAME}")
                setRequestProperty("Accept", "application/vnd.github+json")
            }
            val status = connection.responseCode
            if (status in listOf(301, 302, 303, 307, 308)) {
                val location = connection.getHeaderField("Location") ?: error("Missing update redirect")
                target = URL(target, location)
                connection.disconnect()
            } else {
                if (status !in 200..299) { connection.disconnect(); error("GitHub HTTP $status") }
                return connection
            }
        }
        error("Too many update redirects")
    }
    private fun text(address: String, limit: Int = 131072): String {
        val connection = connection(address)
        try {
            return connection.inputStream.use { input ->
                val bytes = input.readBytesBounded(limit)
                bytes.toString(Charsets.UTF_8)
            }
        } finally { connection.disconnect() }
    }
    private fun java.io.InputStream.readBytesBounded(limit: Int): ByteArray {
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val size = read(buffer)
            if (size < 0) break
            require(output.size() + size <= limit) { "Update metadata is too large" }
            output.write(buffer, 0, size)
        }
        return output.toByteArray()
    }
    @Suppress("DEPRECATION")
    fun verifyApk(context: Context, file: File, expectedVersion: Long) {
        val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
        val archive = context.packageManager.getPackageArchiveInfo(file.absolutePath, flags) ?: error("Invalid APK")
        val installed = context.packageManager.getPackageInfo(context.packageName, flags)
        require(archive.packageName == context.packageName) { "APK package mismatch" }
        val code = if (Build.VERSION.SDK_INT >= 28) archive.longVersionCode else archive.versionCode.toLong()
        require(code == expectedVersion && code > version(context)) { "APK version mismatch" }
        val actual = if (Build.VERSION.SDK_INT >= 28) archive.signingInfo?.apkContentsSigners else archive.signatures
        val trusted = if (Build.VERSION.SDK_INT >= 28) installed.signingInfo?.apkContentsSigners else installed.signatures
        require(!actual.isNullOrEmpty() && !trusted.isNullOrEmpty() && actual.map { it.toCharsString() }.toSet() == trusted.map { it.toCharsString() }.toSet()) { "APK signing certificate mismatch" }
    }
    fun verifyPending(context: Context): File {
        val file = pendingFile(context) ?: error("Update file is missing")
        verifyApk(context, file, preferences(context).getLong("ready_version", 0))
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val bytes = ByteArray(65536)
            while (true) { val count = input.read(bytes); if (count < 0) break; digest.update(bytes, 0, count) }
        }
        require(digest.digest().hex() == preferences(context).getString("ready_sha256", null)) { "APK checksum mismatch" }
        return file
    }
    private fun ByteArray.hex() = joinToString("") { "%02x".format(it) }

    suspend fun check(context: Context, manual: Boolean = false) = withContext(Dispatchers.IO) {
        if (!mutex.tryLock()) return@withContext
        try {
            val prefs = preferences(context)
            if (!manual) {
                val connectivity = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
                if (!automatic(context) || connectivity.isActiveNetworkMetered || System.currentTimeMillis() - prefs.getLong("last_check", 0) < INTERVAL) return@withContext
            }
            if (pendingFile(context) != null) {
                mutableState.value = UpdateState(context.getString(R.string.update_ready, pendingName(context)), pendingNotes(context), true)
                notify(context, mutableState.value.message, mutableState.value.notes)
                return@withContext
            }
            mutableState.value = UpdateState(context.getString(R.string.update_checking))
            val release = JSONObject(text("https://api.github.com/repos/${BuildConfig.UPDATE_REPOSITORY}/releases/latest", 524288))
            require(!release.optBoolean("draft") && !release.optBoolean("prerelease")) { "Release is not stable" }
            val remoteAssets = release.getJSONArray("assets")
            fun asset(name: String): JSONObject = (0 until remoteAssets.length()).map { remoteAssets.getJSONObject(it) }.firstOrNull { it.getString("name") == name } ?: error("Missing release asset: $name")
            val manifest = text(asset("update.json").getString("browser_download_url"))
            val selected = ReleaseManifest.select(manifest, context.packageName, Build.SUPPORTED_ABIS.toList(), version(context))
            prefs.edit().putLong("last_check", System.currentTimeMillis()).apply()
            if (selected == null) { mutableState.value = UpdateState(context.getString(R.string.update_current)); return@withContext }
            val notes = release.optString("body").take(20000)
            val name = release.optString("name", release.getString("tag_name"))
            val temporary = File(directory(context), "update-${selected.versionCode}.download.apk")
            val output = File(directory(context), "update-${selected.versionCode}.apk")
            val digest = MessageDigest.getInstance("SHA-256")
            var received = 0L
            var previousPercent = -1
            val connection = connection(asset(selected.name).getString("browser_download_url"))
            try {
                connection.inputStream.use { input -> temporary.outputStream().use { file ->
                    val buffer = ByteArray(65536)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        received += count
                        require(received <= selected.size) { "APK download exceeds declared size" }
                        file.write(buffer, 0, count); digest.update(buffer, 0, count)
                        val percent = (100 * received / selected.size).toInt()
                        if (percent != previousPercent) {
                            previousPercent = percent
                            mutableState.value = UpdateState(context.getString(R.string.update_downloading, percent), notes)
                            if (percent % 5 == 0) notify(context, mutableState.value.message, notes, percent)
                        }
                    }
                } }
                require(received == selected.size && digest.digest().hex() == selected.sha256) { "APK checksum mismatch" }
                verifyApk(context, temporary, selected.versionCode)
                check(temporary.renameTo(output)) { "Cannot save verified update" }
            } finally { connection.disconnect(); temporary.delete() }
            prefs.edit().putLong("ready_version", selected.versionCode).putString("ready_name", name)
                .putString("ready_notes", notes).putString("ready_sha256", selected.sha256).commit()
            mutableState.value = UpdateState(context.getString(R.string.update_ready, name), notes, true)
            notify(context, mutableState.value.message, notes)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) {
            (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).cancel(NOTIFICATION)
            mutableState.value = UpdateState(context.getString(R.string.update_failed, error.message ?: error.javaClass.simpleName))
        } finally { mutex.unlock() }
    }

    private fun notify(context: Context, title: String, notes: String, percent: Int? = null) {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) manager.createNotificationChannel(NotificationChannel(CHANNEL, context.getString(R.string.app_updates), NotificationManager.IMPORTANCE_DEFAULT))
        val intent = PendingIntent.getActivity(context, NOTIFICATION, Intent(context, UpdateActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(if (percent == null) android.R.drawable.stat_sys_download_done else android.R.drawable.stat_sys_download)
            .setContentTitle(title).setContentText(if (percent == null) context.getString(R.string.update_install) else title)
            .setStyle(NotificationCompat.BigTextStyle().bigText(notes.take(1200))).setContentIntent(intent)
            .setOnlyAlertOnce(true).setAutoCancel(percent == null)
        if (percent != null) notification.setProgress(100, percent, false)
        NotificationManagerCompat.from(context).notify(NOTIFICATION, notification.build())
    }
}
