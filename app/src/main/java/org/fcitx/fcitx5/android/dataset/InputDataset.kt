// SPDX-License-Identifier: GPL-3.0-or-later
package org.fcitx.fcitx5.android.dataset

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.os.Build
import android.os.SystemClock
import android.os.UserManager
import android.view.inputmethod.EditorInfo
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import org.fcitx.fcitx5.android.BuildConfig
import org.fcitx.fcitx5.android.input.SuzuContextPolicy
import org.json.JSONObject
import java.io.File
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import java.util.zip.GZIPOutputStream

/** Opt-in text dataset. Separate from performance telemetry, logcat and clipboard backups. */
object InputDataset {
    const val LIMIT_BYTES = 256L * 1024 * 1024
    private val io = Executors.newSingleThreadExecutor { r -> Thread(r, "InputDataset").apply { priority = Thread.MIN_PRIORITY } }.asCoroutineDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + io)
    private data class Write(val generation: Long, val json: String?, val barrier: CompletableDeferred<Unit>? = null)
    private val queue = Channel<Write>(2048)
    private val generation = AtomicLong()
    private val eventNumber = AtomicLong()
    @Volatile private var app: Context? = null
    @Volatile private var active: String? = null
    @Volatile private var editorPackage = ""
    private var db: SQLiteDatabase? = null
    private var bytes = -1L
    private val pending = java.util.concurrent.ConcurrentLinkedQueue<JSONObject>()
    private val pendingKeys = java.util.concurrent.ConcurrentLinkedQueue<JSONObject>()
    init { scope.launch { for (item in queue) {
        if (item.barrier != null) { item.barrier.complete(Unit); continue }
        if (item.generation != generation.get()) continue
        try {
            val text = item.json ?: continue
            val size = text.toByteArray(Charsets.UTF_8).size
            val database = database()
            if (bytes + size > LIMIT_BYTES) { pause("storage_limit"); continue }
            database.insertOrThrow("events", null, ContentValues().apply { put("json", text); put("bytes", size) })
            bytes += size
            app?.let { TelemetrySync.dataWritten(it) }
        } catch (_: Exception) { pause("write_error") }
    } } }
    fun initialize(context: Context) { app = context.applicationContext }
    private fun prefs() = checkNotNull(app).getSharedPreferences("input_dataset", Context.MODE_PRIVATE)
    fun enabled(context: Context): Boolean { initialize(context); return runCatching { prefs().getBoolean("enabled", false) }.getOrDefault(false) }
    fun setEnabled(context: Context, value: Boolean) {
        initialize(context); active = null; pending.clear(); pendingKeys.clear()
        prefs().edit().putBoolean("enabled", value).remove("pause_reason").apply()
    }
    private fun pause(reason: String) {
        active = null; pending.clear(); pendingKeys.clear()
        runCatching { prefs().edit().putBoolean("enabled", false).putString("pause_reason", reason).apply() }
        app?.let { TelemetrySettings.setPerformance(it, false) }
    }
    fun pauseReason(): String = runCatching { prefs().getString("pause_reason", "").orEmpty() }.getOrDefault("")
    fun excluded(): Set<String> = runCatching { prefs().getStringSet("excluded", emptySet())!!.toSet() }.getOrDefault(emptySet())
    fun setExcluded(values: Set<String>) { active = null; pending.clear(); pendingKeys.clear(); prefs().edit().putStringSet("excluded", values).apply() }
    fun observedApps(): Set<String> = runCatching { prefs().getStringSet("observed_apps", emptySet())!!.toSet() }.getOrDefault(emptySet())
    fun editorChanged(context: Context, info: EditorInfo?) {
        initialize(context); active = null; pending.clear(); pendingKeys.clear(); editorPackage = info?.packageName.orEmpty()
    }
    fun endEditor() { active = null; pending.clear(); pendingKeys.clear(); editorPackage = "" }
    // Called on Main before every engine operation; native never guesses field privacy.
    fun session(context: Context, info: EditorInfo?): String? {
        initialize(context)
        val unlocked = Build.VERSION.SDK_INT < 24 || (context.getSystemService(Context.USER_SERVICE) as UserManager).isUserUnlocked
        if (!unlocked || !enabled(context) || !SuzuContextPolicy.allows(info) || info?.packageName in excluded()) {
            active = null; pending.clear(); pendingKeys.clear(); return null
        }
        val pkg = info?.packageName.orEmpty()
        if (pkg != editorPackage) { active = null; pending.clear(); pendingKeys.clear(); editorPackage = pkg }
        active?.let { return it }
        val id = UUID.randomUUID().toString(); active = id
        prefs().edit().putStringSet("observed_apps", (observedApps() + pkg).toList().takeLast(100).toSet()).apply()
        record(id, JSONObject().put("type", "session_start").put("application", pkg)
            .put("input_type", info?.inputType).put("ime_options", info?.imeOptions)
            .put("label_kind", "observed_behavior"))
        return id
    }
    fun currentSession(): String? = active
    fun record(session: String?, data: JSONObject) {
        if (session == null || session != active) return
        val line = JSONObject(data.toString()).put("session_id", session).put("event_no", eventNumber.incrementAndGet())
            .put("wall_time_ms", System.currentTimeMillis()).put("elapsed_ms", SystemClock.elapsedRealtime())
            .put("app_version", BuildConfig.VERSION_NAME).put("build_commit", BuildConfig.BUILD_GIT_HASH).toString()
        if (queue.trySend(Write(generation.get(), line)).isFailure) pause("queue_full")
    }
    /** Called synchronously on Fcitx thread, before the lossy UI SharedFlow. Never log payloads. */
    fun nativeEvent(payload: String) {
        val owner = active ?: return
        runCatching {
            val data = JSONObject(payload)
            if (data.optString("session_id") != owner) return
            if (data.optString("type") == "engine_commit") {
                if (pending.size >= 32) pending.poll()
                pending.add(JSONObject(data.toString()))
            }
            if (data.optString("type") == "action") {
                val action = data.optJSONObject("action")
                if (action?.optString("kind") == "key" && !action.optBoolean("up")) {
                    if (pendingKeys.size >= 64) pendingKeys.poll()
                    pendingKeys.add(JSONObject(action.toString()).put("session_id", owner))
                }
            }
            record(owner, data)
        }
    }
    fun editorCommit(text: String, source: String, success: Boolean, start: Int, replacedEnd: Int) {
        val owner = active ?: return
        val native = if (source == "engine") pending.poll()?.takeIf {
            it.optString("session_id") == owner && it.optString("text") == text
        } else null
        // Old UI events may arrive after an editor switch. Never persist unowned engine text.
        var key: JSONObject? = null
        if (source == "engine_key" && text.codePointCount(0, text.length) == 1) {
            val code = text.codePointAt(0)
            while (pendingKeys.isNotEmpty()) {
                val candidate = pendingKeys.poll() ?: break
                if (candidate.optString("session_id") == owner && (candidate.optInt("sym") and 0xffffff) == code) { key = candidate; break }
            }
        }
        if ((source == "engine" && native == null) || (source == "engine_key" && key == null)) {
            record(owner, JSONObject().put("type", "unattributed_commit").put("source", source).put("chars", text.length))
            return
        }
        record(owner, JSONObject().put("type", "editor_commit").put("text", text).put("source", source)
            .put("success", success).put("replace_start", start).put("replace_end", replacedEnd)
            .put("engine_action", native?.optJSONObject("action") ?: JSONObject.NULL)
            .put("key_action", key ?: JSONObject.NULL)
            .put("engine_decision_id", native?.optJSONObject("action")?.optLong("decision_id") ?: 0))
    }
    fun event(type: String, fields: JSONObject = JSONObject()) { record(active, fields.put("type", type)) }
    fun performance(context: Context, data: JSONObject) {
        initialize(context)
        if (!TelemetrySettings.performance(context) || (Build.VERSION.SDK_INT >= 24 && !(context.getSystemService(Context.USER_SERVICE) as UserManager).isUserUnlocked)) return
        val line = JSONObject().put("type", "completion_performance").put("wall_time_ms", System.currentTimeMillis())
            .put("app_version", BuildConfig.VERSION_NAME).put("build_commit", BuildConfig.BUILD_GIT_HASH).put("performance", data).toString()
        if (queue.trySend(Write(generation.get(), line)).isFailure) pause("queue_full")
    }
    private fun database(): SQLiteDatabase {
        db?.let { return it }
        val context = checkNotNull(app)
        val file = File(context.noBackupFilesDir, "input-dataset/events.sqlite"); check(file.parentFile!!.mkdirs() || file.parentFile!!.isDirectory)
        return SQLiteDatabase.openOrCreateDatabase(file, null).also {
            it.enableWriteAheadLogging()
            it.execSQL("CREATE TABLE IF NOT EXISTS events (seq INTEGER PRIMARY KEY AUTOINCREMENT, json TEXT NOT NULL, bytes INTEGER NOT NULL)")
            it.execSQL("CREATE TABLE IF NOT EXISTS metadata (key TEXT PRIMARY KEY, value TEXT NOT NULL)")
            it.execSQL("CREATE TABLE IF NOT EXISTS uploads (destination TEXT PRIMARY KEY, last_seq INTEGER NOT NULL)")
            it.execSQL("CREATE TABLE IF NOT EXISTS batches (destination TEXT PRIMARY KEY, id TEXT NOT NULL, generation TEXT NOT NULL, first_seq INTEGER NOT NULL, last_seq INTEGER NOT NULL, event_count INTEGER NOT NULL, sha256 TEXT NOT NULL, body BLOB NOT NULL)")
            for (key in listOf("installation", "generation")) it.execSQL("INSERT OR IGNORE INTO metadata(key,value) VALUES(?,?)", arrayOf(key, UUID.randomUUID().toString()))
            bytes = it.rawQuery("SELECT COALESCE(SUM(bytes),0) FROM events", null).use { c -> c.moveToFirst(); c.getLong(0) }
            db = it
        }
    }
    private suspend fun flush() { val done = CompletableDeferred<Unit>(); queue.send(Write(generation.get(), null, done)); done.await() }
    suspend fun stats(): Pair<Long, Long> { flush(); return withContext(io) {
        val count = database().rawQuery("SELECT COUNT(*) FROM events", null).use { it.moveToFirst(); it.getLong(0) }
        count to bytes
    } }
    private fun metadata(key: String) = database().rawQuery("SELECT value FROM metadata WHERE key=?", arrayOf(key)).use { it.moveToFirst(); it.getString(0) }
    private fun uploaded(destination: String) = database().rawQuery("SELECT last_seq FROM uploads WHERE destination=?", arrayOf(destination)).use { if (it.moveToFirst()) it.getLong(0) else 0L }
    suspend fun pending(destination: String): TelemetryPending { flush(); return withContext(io) {
        database().rawQuery("SELECT COUNT(*),COALESCE(SUM(bytes),0) FROM events WHERE seq>?", arrayOf(uploaded(destination).toString())).use { it.moveToFirst(); TelemetryPending(it.getLong(0), it.getLong(1)) }
    } }
    /** Reuses the same persisted bytes and ID until a matching server receipt is acknowledged. */
    suspend fun prepareBatch(destination: String): TelemetryBatch? { flush(); return withContext(io) {
        val database = database()
        database.rawQuery("SELECT id,generation,first_seq,last_seq,event_count,sha256,body FROM batches WHERE destination=?", arrayOf(destination)).use { c ->
            if (c.moveToFirst()) return@withContext TelemetryBatch(c.getString(0), c.getString(1), c.getLong(2), c.getLong(3), c.getInt(4), c.getString(5), c.getBlob(6))
        }
        val currentGeneration = metadata("generation"); val installation = metadata("installation")
        val raw = ByteArrayOutputStream(); var first = 0L; var last = 0L; var count = 0
        database.rawQuery("SELECT seq,json FROM events WHERE seq>? ORDER BY seq LIMIT 512", arrayOf(uploaded(destination).toString())).use { c ->
            while (c.moveToNext()) {
                val seq = c.getLong(0)
                val event = JSONObject(c.getString(1)).put("record_id", seq).put("installation_id", installation).put("generation", currentGeneration).put("schema", 1)
                val line = (event.toString() + "\n").toByteArray(Charsets.UTF_8)
                if (count > 0 && raw.size() + line.size > 512 * 1024) break
                if (count == 0) first = seq
                last = seq; count++; raw.write(line)
            }
        }
        if (count == 0) return@withContext null
        val compressed = ByteArrayOutputStream().also { target -> GZIPOutputStream(target).use { it.write(raw.toByteArray()) } }.toByteArray()
        if (compressed.size > 1024 * 1024) throw TelemetryFailure("batch_too_large")
        val batch = TelemetryBatch(UUID.randomUUID().toString(), currentGeneration, first, last, count, telemetrySha256(compressed), compressed)
        database.insertOrThrow("batches", null, ContentValues().apply {
            put("destination", destination); put("id", batch.id); put("generation", batch.generation); put("first_seq", first)
            put("last_seq", last); put("event_count", count); put("sha256", batch.sha256); put("body", compressed)
        })
        batch
    } }
    suspend fun acknowledge(destination: String, batch: TelemetryBatch): Boolean = withContext(io) {
        val database = database()
        if (metadata("generation") != batch.generation) return@withContext false
        val exists = database.rawQuery("SELECT id,sha256 FROM batches WHERE destination=?", arrayOf(destination)).use { c -> c.moveToFirst() && c.getString(0) == batch.id && c.getString(1) == batch.sha256 }
        if (!exists) return@withContext false
        database.beginTransaction()
        try {
            database.execSQL("INSERT OR REPLACE INTO uploads(destination,last_seq) VALUES(?,?)", arrayOf<Any>(destination, batch.last))
            database.delete("batches", "destination=? AND id=?", arrayOf(destination, batch.id))
            database.setTransactionSuccessful()
        } finally { database.endTransaction() }
        true
    }
    suspend fun export(output: OutputStream) {
        flush()
        withContext(io) {
            ZipOutputStream(output).use { zip ->
                val manifest = JSONObject().put("schema", 1).put("task", "pinyin-candidate-ranking-and-completion")
                    .put("installation_id", metadata("installation")).put("generation", metadata("generation"))
                    .put("exported_at_ms", System.currentTimeMillis()).put("label_kind", "observed_behavior_not_human_gold")
                    .put("app_version", BuildConfig.VERSION_NAME).put("build_commit", BuildConfig.BUILD_GIT_HASH)
                    .put("device", JSONObject().put("manufacturer", Build.MANUFACTURER).put("model", Build.MODEL).put("sdk", Build.VERSION.SDK_INT))
                    .put("candidate_pool", "initial_16_plus_delivered_pages").put("candidate_consumed_ranges", JSONObject.NULL)
                    .put("full_engine_replay", false).put("limit_bytes", LIMIT_BYTES).put("pause_reason", pauseReason())
                zip.putNextEntry(ZipEntry("manifest.json")); zip.write(manifest.toString(2).toByteArray()); zip.closeEntry()
                zip.putNextEntry(ZipEntry("events.jsonl"))
                database().rawQuery("SELECT seq,json FROM events ORDER BY seq", null).use { cursor -> while (cursor.moveToNext()) {
                    val event = JSONObject(cursor.getString(1)).put("record_id", cursor.getLong(0))
                    zip.write((event.toString() + "\n").toByteArray(Charsets.UTF_8))
                } }
                zip.closeEntry()
            }
        }
    }
    suspend fun clear() {
        generation.incrementAndGet(); active = null; pending.clear(); pendingKeys.clear(); flush()
        withContext(io) {
            val database = database(); database.beginTransaction()
            try {
                database.execSQL("DELETE FROM events"); database.execSQL("DELETE FROM batches"); database.execSQL("DELETE FROM uploads")
                database.execSQL("UPDATE metadata SET value=? WHERE key='generation'", arrayOf(UUID.randomUUID().toString())); database.setTransactionSuccessful()
            } finally { database.endTransaction() }
            database.execSQL("VACUUM"); bytes = 0
        }
    }
}
