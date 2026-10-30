// SPDX-License-Identifier: GPL-3.0-or-later
package org.fcitx.fcitx5.android.data.clipboard

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import androidx.annotation.Keep
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.prefs.ManagedPreference
import org.fcitx.fcitx5.android.utils.appContext

object ScreenshotMonitor {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private val preferences get() = appContext.getSharedPreferences("screenshot_capture", Context.MODE_PRIVATE)
    private val catalog by lazy { ScreenshotCatalog(appContext) }
    private val mutableStatus = MutableStateFlow(R.string.screenshot_status_off)
    val status = mutableStatus.asStateFlow()
    private var initialized = false
    private var registered = false
    private var scheduled: Job? = null
    private val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) = schedule()
        override fun onChange(selfChange: Boolean, uri: Uri?) = schedule()
    }
    @Keep private val changed = ManagedPreference.OnChangeListener<Boolean> { _, _ -> refresh() }

    fun permission(): String = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_IMAGES else Manifest.permission.READ_EXTERNAL_STORAGE
    fun hasAccess() = ContextCompat.checkSelfPermission(appContext, permission()) == PackageManager.PERMISSION_GRANTED
    fun hasPartialAccess() = Build.VERSION.SDK_INT >= 34 && ContextCompat.checkSelfPermission(appContext, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) == PackageManager.PERMISSION_GRANTED
    fun permissions(): Array<String> = if (Build.VERSION.SDK_INT >= 34) arrayOf(permission(), Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) else arrayOf(permission())

    fun init() {
        if (initialized) return
        initialized = true
        AppPrefs.getInstance().clipboard.apply {
            clipboardScreenshots.registerOnChangeListener(changed)
            clipboardListening.registerOnChangeListener(changed)
        }
        refresh()
    }

    suspend fun prepareEnable() = withContext(Dispatchers.IO) {
        mutex.withLock {
            check(hasAccess())
            save(ScreenshotCheckpoint(System.currentTimeMillis(), catalog.latestId(), catalog.version()))
        }
    }

    private fun save(state: ScreenshotCheckpoint) { check(preferences.edit().putString("checkpoint", state.encode()).commit()) }

    fun refresh() {
        if (!initialized || FcitxApplication.getInstance().isDirectBootMode) return
        val prefs = AppPrefs.getInstance().clipboard
        val enabled = prefs.clipboardScreenshots.getValue() && prefs.clipboardListening.getValue()
        if (!enabled || !hasAccess()) {
            if (registered) { appContext.contentResolver.unregisterContentObserver(observer); registered = false }
            scheduled?.cancel(); scheduled = null
            mutableStatus.value = if (enabled) R.string.screenshot_status_permission else R.string.screenshot_status_off
            return
        }
        if (!registered) {
            try {
                val collections = mutableSetOf(MediaStore.Images.Media.EXTERNAL_CONTENT_URI)
                if (Build.VERSION.SDK_INT >= 29) MediaStore.getExternalVolumeNames(appContext).forEach { collections.add(MediaStore.Images.Media.getContentUri(it)) }
                collections.forEach { appContext.contentResolver.registerContentObserver(it, true, observer) }
                registered = true
            } catch (_: SecurityException) {
                runCatching { appContext.contentResolver.unregisterContentObserver(observer) }
                registered = false
                mutableStatus.value = R.string.screenshot_status_permission
                return
            }
        }
        mutableStatus.value = R.string.screenshot_status_active
        schedule()
    }

    private fun schedule() {
        if (!registered) return
        scheduled?.cancel()
        scheduled = scope.launch {
            delay(350)
            repeat(3) { attempt ->
                val retry = try { scan() }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: SecurityException) { mutableStatus.value = R.string.screenshot_status_permission; false }
                catch (_: Exception) { mutableStatus.value = R.string.screenshot_status_error; false }
                if (!retry) return@launch
                delay((attempt + 1) * 1000L)
            }
        }
    }

    private suspend fun scan(): Boolean = mutex.withLock {
        val prefs = AppPrefs.getInstance().clipboard
        if (!hasAccess() || !prefs.clipboardScreenshots.getValue() || !prefs.clipboardListening.getValue()) return@withLock false
        var state = runCatching { ScreenshotCheckpoint.decode(preferences.getString("checkpoint", "")!!) }.getOrNull()
        val version = catalog.version()
        if (state == null || state.version != version) {
            state = ScreenshotCheckpoint(System.currentTimeMillis(), catalog.latestId(), version)
            save(state)
            return@withLock false
        }
        val retry = ScreenshotBatch.capture(state, catalog.recent(state), prefs.clipboardHistoryLimit.getValue()) { row ->
            if (!prefs.clipboardScreenshots.getValue() || !prefs.clipboardListening.getValue()) return@capture false
            try { ClipboardManager.captureScreenshot(row.uri, row.timestamp) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (denied: SecurityException) { throw denied }
            catch (_: ClipboardImageStore.TooLarge) { mutableStatus.value = R.string.screenshot_status_too_large; true }
            catch (_: Exception) { mutableStatus.value = R.string.screenshot_status_error; false }
        }
        save(state)
        retry
    }
}
