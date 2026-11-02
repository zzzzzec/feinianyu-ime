// SPDX-License-Identifier: GPL-3.0-or-later
package org.fcitx.fcitx5.android.dataset

import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ComponentName
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import android.os.SystemClock
import android.os.UserManager
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class TelemetryBatch(val id: String, val generation: String, val first: Long, val last: Long, val count: Int, val sha256: String, val body: ByteArray)
data class TelemetryPending(val count: Long, val bytes: Long)
internal class TelemetryFailure(val reason: String, val retry: Boolean = false) : Exception(reason)
internal fun interface TelemetryTransport { suspend fun send(connection: TelemetryConnection, batch: TelemetryBatch): JSONObject }

object TelemetrySync {
    private const val PERIODIC = 714340
    private const val IMMEDIATE = 714341
    private val mutex = Mutex()
    @Volatile private var lastRequested = 0L
    private fun scheduler(context: Context) = context.getSystemService(JobScheduler::class.java)
    fun schedule(context: Context) {
        val jobs = scheduler(context)
        if (!TelemetrySettings.enabled(context)) { jobs.cancel(PERIODIC); jobs.cancel(IMMEDIATE); return }
        jobs.schedule(builder(context, PERIODIC).setPeriodic(15 * 60 * 1000L).setPersisted(true).build())
    }
    private fun builder(context: Context, id: Int) = JobInfo.Builder(id, ComponentName(context, TelemetryJobService::class.java))
        .setRequiredNetworkType(if (TelemetrySettings.wifiOnly(context)) JobInfo.NETWORK_TYPE_UNMETERED else JobInfo.NETWORK_TYPE_ANY)
        .setRequiresCharging(TelemetrySettings.chargingOnly(context)).apply { if (Build.VERSION.SDK_INT >= 26) setRequiresBatteryNotLow(true) }
        .setBackoffCriteria(30000L, JobInfo.BACKOFF_POLICY_EXPONENTIAL)
    fun request(context: Context) {
        if (TelemetrySettings.enabled(context)) scheduler(context).schedule(builder(context, IMMEDIATE).setMinimumLatency(0).build())
    }
    fun dataWritten(context: Context) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastRequested < 60000) return
        lastRequested = now; request(context)
    }
    private fun allowed(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= 24 && !(context.getSystemService(Context.USER_SERVICE) as UserManager).isUserUnlocked) return false
        val network = context.getSystemService(ConnectivityManager::class.java)
        val caps = network.getNetworkCapabilities(network.activeNetwork) ?: return false
        if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) || !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) return false
        if (TelemetrySettings.wifiOnly(context) && !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)) return false
        if (TelemetrySettings.chargingOnly(context) && !context.getSystemService(BatteryManager::class.java).isCharging) return false
        return true
    }
    suspend fun checkConnection(context: Context) = withContext(Dispatchers.IO) {
        val config = TelemetrySettings.connection(context) ?: throw TelemetryFailure("pairing_required")
        val connection = open(config.endpoint + "/v1/device", config)
        try {
            connection.requestMethod = "GET"
            connection.setRequestProperty("Authorization", "Bearer " + config.credentials.uploadToken)
            if (connection.responseCode != 200) throw TelemetryFailure(if (connection.responseCode in listOf(401, 403)) "access_denied" else "http_error")
            val response = readJson(connection)
            if (response.optInt("schema") != 1 || response.optString("device_id") != config.credentials.deviceId) throw TelemetryFailure("protocol_error")
        } finally { connection.disconnect() }
    }
    private fun open(url: String, config: TelemetryConnection) = (URL(url).openConnection() as HttpURLConnection).apply {
        instanceFollowRedirects = false; connectTimeout = 15000; readTimeout = 20000
        useCaches = false
        if (config.credentials.accessId.isNotEmpty()) {
            setRequestProperty("CF-Access-Client-Id", config.credentials.accessId)
            setRequestProperty("CF-Access-Client-Secret", config.credentials.accessSecret)
        }
    }
    private fun readJson(connection: HttpURLConnection): JSONObject {
        val bytes = connection.inputStream.use { it.readNBytesCompat(32768) }
        return runCatching { JSONObject(bytes.toString(Charsets.UTF_8)) }.getOrElse { throw TelemetryFailure("protocol_error") }
    }
    private fun java.io.InputStream.readNBytesCompat(limit: Int): ByteArray {
        val result = java.io.ByteArrayOutputStream(); val buffer = ByteArray(4096)
        while (true) { val count = read(buffer); if (count < 0) break; if (result.size() + count > limit) throw TelemetryFailure("protocol_error"); result.write(buffer, 0, count) }
        return result.toByteArray()
    }
    private val http = TelemetryTransport { config, batch -> withContext(Dispatchers.IO) {
        val connection = open(config.endpoint + "/v1/batches/" + batch.id, config)
        try {
            connection.requestMethod = "POST"; connection.doOutput = true; connection.setFixedLengthStreamingMode(batch.body.size)
            connection.setRequestProperty("Authorization", "Bearer " + config.credentials.uploadToken)
            connection.setRequestProperty("Content-Type", "application/gzip")
            connection.setRequestProperty("X-Telemetry-Generation", batch.generation)
            connection.setRequestProperty("X-Telemetry-First-Seq", batch.first.toString())
            connection.setRequestProperty("X-Telemetry-Last-Seq", batch.last.toString())
            connection.setRequestProperty("X-Telemetry-Event-Count", batch.count.toString())
            connection.setRequestProperty("X-Content-SHA256", batch.sha256)
            connection.outputStream.use { it.write(batch.body) }
            when (val status = connection.responseCode) {
                200, 201 -> readJson(connection)
                401, 403 -> throw TelemetryFailure("access_denied")
                409 -> throw TelemetryFailure("batch_conflict")
                413 -> throw TelemetryFailure("batch_too_large")
                429 -> throw TelemetryFailure("rate_limited", true)
                in 500..599 -> throw TelemetryFailure("server_error", true)
                else -> throw TelemetryFailure(if (status in 300..399) "redirect_rejected" else "http_error")
            }
        } finally { connection.disconnect() }
    } }
    /** Returns whether the scheduler should retry. A mismatched ACK never advances the durable cursor. */
    suspend fun sync(context: Context): Boolean = syncWith(context, http, true)
    internal suspend fun syncWith(context: Context, transport: TelemetryTransport, enforceNetwork: Boolean): Boolean = mutex.withLock {
        InputDataset.initialize(context)
        if (!TelemetrySettings.enabled(context)) return@withLock false
        val config = TelemetrySettings.connection(context) ?: run { TelemetrySettings.result(context, "pairing_required"); return@withLock false }
        if (enforceNetwork && !allowed(context)) { TelemetrySettings.result(context, "waiting_network"); return@withLock true }
        try {
            var complete = false
            for (iteration in 0 until 8) {
                currentCoroutineContext().ensureActive()
                if (!TelemetrySettings.enabled(context) || TelemetrySettings.revision(context) != config.revision) return@withLock false
                val next = InputDataset.prepareBatch(config.destination)
                if (next == null) { complete = true; break }
                val batch = next
                val ack = transport.send(config, batch)
                currentCoroutineContext().ensureActive()
                val match = ack.optInt("schema") == 1 && ack.optString("status") == "stored" && ack.optString("device_id") == config.credentials.deviceId &&
                    ack.optString("generation") == batch.generation && ack.optString("batch_id") == batch.id && ack.optString("sha256") == batch.sha256 &&
                    ack.optLong("first_seq") == batch.first && ack.optLong("last_seq") == batch.last && ack.optInt("event_count") == batch.count && ack.optInt("bytes") == batch.body.size
                if (!match) throw TelemetryFailure("receipt_mismatch")
                if (TelemetrySettings.revision(context) != config.revision || !InputDataset.acknowledge(config.destination, batch)) return@withLock false
                TelemetrySettings.result(context, "")
            }
            if (!complete) request(context)
            false
        } catch (error: CancellationException) { throw error }
        catch (error: TelemetryFailure) { TelemetrySettings.result(context, error.reason); error.retry }
        catch (_: Exception) { TelemetrySettings.result(context, "network_error"); true }
    }
}
