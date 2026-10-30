/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2023 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.data.clipboard

import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import androidx.annotation.Keep
import androidx.room.Room
import kotlinx.coroutines.*
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.clipboard.db.ClipboardDao
import org.fcitx.fcitx5.android.data.clipboard.db.ClipboardDatabase
import org.fcitx.fcitx5.android.data.clipboard.db.ClipboardEntry
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.prefs.ManagedPreference
import org.fcitx.fcitx5.android.utils.*
import timber.log.Timber

object ClipboardManager : ClipboardManager.OnPrimaryClipChangedListener,
    CoroutineScope by CoroutineScope(SupervisorJob() + Dispatchers.Default) {
    private lateinit var dao: ClipboardDao
    private lateinit var repository: ClipboardRepository
    private val clipboardManager = appContext.clipboardManager
    private val onUpdateListeners = WeakHashSet<OnClipboardUpdateListener>()
    private val enabledPref = AppPrefs.getInstance().clipboard.clipboardListening
    private val limitPref = AppPrefs.getInstance().clipboard.clipboardHistoryLimit

    fun interface OnClipboardUpdateListener { fun onUpdate(entry: ClipboardEntry?) }
    fun addOnUpdateListener(listener: OnClipboardUpdateListener) { onUpdateListeners.add(listener) }
    fun removeOnUpdateListener(listener: OnClipboardUpdateListener) { onUpdateListeners.remove(listener) }
    var transformer: ((String) -> String)? = null
    val itemCount get() = repository.itemCount
    val lastEntry get() = repository.lastEntry

    @Keep private val enabledListener = ManagedPreference.OnChangeListener<Boolean> { _, value ->
        if (value) { clipboardManager.addPrimaryClipChangedListener(this); onPrimaryClipChanged() }
        else clipboardManager.removePrimaryClipChangedListener(this)
    }
    @Keep private val limitListener = ManagedPreference.OnChangeListener<Int> { _, _ -> launch { repository.trim() } }

    fun init(context: Context) {
        val database = Room.databaseBuilder(context, ClipboardDatabase::class.java, "clbdb").build()
        dao = database.clipboardDao()
        repository = ClipboardRepository(database, ClipboardImages.store, { limitPref.getValue() }, onUpdate = { entry ->
            launch(Dispatchers.Main.immediate) { onUpdateListeners.forEach { it.onUpdate(entry) } }
        }, additionalReferences = { setOfNotNull(ClipboardImages.primaryFile) })
        enabledListener.onChange(enabledPref.key, enabledPref.getValue())
        enabledPref.registerOnChangeListener(enabledListener)
        limitPref.registerOnChangeListener(limitListener)
        launch { repository.initialize(); repository.trim() }
    }

    suspend fun get(id: Int) = dao.get(id)
    suspend fun haveUnpinned() = dao.haveUnpinned()
    fun allEntries() = dao.allEntries()
    suspend fun pin(id: Int) = repository.pin(id, true)
    suspend fun unpin(id: Int) = repository.pin(id, false)
    suspend fun updateText(id: Int, text: String) = repository.updateText(id, text)
    suspend fun delete(id: Int) = repository.delete(id)
    suspend fun deleteAll(skipPinned: Boolean = true) = repository.deleteAll(skipPinned)
    suspend fun undoDelete(vararg ids: Int) = repository.undoDelete(*ids)
    suspend fun realDelete() = repository.realDelete()
    suspend fun nukeTable() = repository.clear()

    suspend fun captureScreenshot(uri: android.net.Uri, timestamp: Long): Boolean = withContext(Dispatchers.IO) {
        val clip = android.content.ClipData.newUri(appContext.contentResolver, "", uri)
        val image = ClipboardImages.store.open(clip) ?: return@withContext false
        repository.capture(ClipboardEntry(text = "", timestamp = timestamp), image,
            promoteToLast = System.currentTimeMillis() - timestamp <= 30_000 && timestamp >= (lastEntry?.timestamp ?: 0L))
        true
    }

    private var lastClipTimestamp = -1L
    private var lastClipHash = 0

    override fun onPrimaryClipChanged() {
        val clip = try { clipboardManager.primaryClip } catch (_: Exception) { return }
        if (clip == null) {
            if (InputMethodUtil.isSelected()) { ClipboardImages.primaryFile = null; launch { repository.trim() } }
            return
        }
        ClipboardImages.primaryFile = ClipboardImages.store.ownedName(clip.takeIf { it.itemCount > 0 }?.getItemAt(0)?.uri)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val timestamp = clip.description.timestamp
            if (timestamp == lastClipTimestamp) return
            lastClipTimestamp = timestamp
        } else {
            val timestamp = System.currentTimeMillis()
            val hash = clip.hashCode()
            if (timestamp - lastClipTimestamp < 100L && hash == lastClipHash) return
            lastClipTimestamp = timestamp; lastClipHash = hash
        }
        val image = try { ClipboardImages.store.open(clip) } catch (error: Exception) {
            launch { withContext(Dispatchers.Main) { appContext.toast(R.string.clipboard_image_read_failed) } }
            return
        }
        val entry = if (image != null) ClipboardEntry(text = "", timestamp = clip.timestamp(), sensitive = ClipboardEntry.isSensitive(clip.description))
            else ClipboardEntry.fromClipData(clip, transformer) ?: return
        launch {
            try { repository.capture(entry, image) }
            catch (cancelled: CancellationException) { image?.close(); throw cancelled }
            catch (error: Exception) {
                // Never include clipboard text or source URIs in runtime logs.
                Timber.w("Clipboard capture failed: ${error.javaClass.simpleName}")
                if (image != null) withContext(Dispatchers.Main) {
                    appContext.toast(if (error is ClipboardImageStore.TooLarge) R.string.clipboard_image_too_large else R.string.clipboard_image_read_failed)
                }
            }
        }
    }
}
