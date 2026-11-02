// SPDX-License-Identifier: GPL-3.0-or-later
package org.fcitx.fcitx5.android.dataset

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import android.util.Base64
import org.json.JSONObject
import java.io.File
import java.net.URI
import java.security.KeyStore
import java.security.MessageDigest
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

data class TelemetryCredentials(val deviceId: String, val uploadToken: String, val accessId: String, val accessSecret: String) {
    override fun toString() = "TelemetryCredentials(redacted)"
}
data class TelemetryConnection(val endpoint: String, val credentials: TelemetryCredentials, val revision: Long) {
    val destination: String get() = telemetrySha256((endpoint + "\n" + credentials.deviceId).toByteArray())
    override fun toString() = "TelemetryConnection(redacted)"
}
fun telemetrySha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

object TelemetrySettings {
    const val DEFAULT_ENDPOINT = "https://feinianyu-ime-telemetry-ingest.zec2716548913.workers.dev"
    private const val KEY_ALIAS = "feinianyu.telemetry.aes.v1"
    private fun prefs(context: Context) = context.getSharedPreferences("telemetry", Context.MODE_PRIVATE)
    private fun file(context: Context) = AtomicFile(File(context.noBackupFilesDir, "telemetry/credentials.bin"))
    fun endpoint(context: Context) = prefs(context).getString("endpoint", DEFAULT_ENDPOINT)!!
    fun revision(context: Context) = prefs(context).getLong("revision", 0)
    fun enabled(context: Context) = prefs(context).getBoolean("enabled", false)
    fun wifiOnly(context: Context) = prefs(context).getBoolean("wifi_only", true)
    fun chargingOnly(context: Context) = prefs(context).getBoolean("charging_only", false)
    fun performance(context: Context) = prefs(context).getBoolean("performance", false)
    fun setPerformance(context: Context, value: Boolean) { prefs(context).edit().putBoolean("performance", value).apply() }
    fun setEnabled(context: Context, value: Boolean) {
        require(!value || connection(context) != null) { "pairing_required" }
        prefs(context).edit().putBoolean("enabled", value).apply(); TelemetrySync.schedule(context)
    }
    fun setConstraints(context: Context, wifi: Boolean, charging: Boolean) {
        prefs(context).edit().putBoolean("wifi_only", wifi).putBoolean("charging_only", charging).apply(); TelemetrySync.schedule(context)
    }
    fun normalizeEndpoint(value: String): String {
        val uri = runCatching { URI(value.trim()) }.getOrElse { error("invalid_address") }
        require(uri.scheme == "https" && !uri.host.isNullOrBlank() && uri.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null && uri.port in -1..65535) { "invalid_address" }
        require(uri.host.length <= 253 && value.length <= 2048) { "invalid_address" }
        return URI("https", null, uri.host.lowercase(), uri.port, uri.path.trimEnd('/'), null, null).toASCIIString()
    }
    @Synchronized fun setEndpoint(context: Context, value: String) {
        val normalized = normalizeEndpoint(value)
        if (normalized == endpoint(context)) return
        disconnect(context)
        prefs(context).edit().putString("endpoint", normalized).commit()
    }
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    @Synchronized fun importPairing(context: Context, text: String) {
        require(text.toByteArray().size <= 32768) { "invalid_pairing" }
        val obj = runCatching { JSONObject(text) }.getOrElse { error("invalid_pairing") }
        require(obj.optInt("schema") == 1) { "invalid_pairing" }
        val endpoint = normalizeEndpoint(obj.optString("server_url"))
        val device = obj.optString("device_id")
        require(runCatching { UUID.fromString(device).toString() == device }.getOrDefault(false)) { "invalid_pairing" }
        val token = obj.optString("upload_token")
        val accessId = obj.optString("access_client_id")
        val accessSecret = obj.optString("access_client_secret")
        require(token.matches(Regex("[A-Za-z0-9_-]{43,128}"))) { "invalid_pairing" }
        require((accessId.isEmpty() && accessSecret.isEmpty()) || (accessId.matches(Regex("[A-Za-z0-9_.-]{10,256}")) && accessSecret.matches(Regex("[A-Za-z0-9_-]{20,256}")))) { "invalid_pairing" }
        val stored = JSONObject().put("endpoint", endpoint).put("device_id", device).put("upload_token", token)
            .put("access_client_id", accessId).put("access_client_secret", accessSecret)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        val encrypted = cipher.doFinal(stored.toString().toByteArray(Charsets.UTF_8))
        val envelope = JSONObject().put("iv", Base64.encodeToString(cipher.iv, Base64.NO_WRAP)).put("ciphertext", Base64.encodeToString(encrypted, Base64.NO_WRAP)).toString().toByteArray()
        val destination = file(context); destination.baseFile.parentFile!!.mkdirs()
        val output = destination.startWrite()
        try { output.write(envelope); destination.finishWrite(output) } catch (error: Exception) { destination.failWrite(output); throw error }
        prefs(context).edit().putString("endpoint", endpoint).putBoolean("enabled", false).putLong("revision", revision(context) + 1).remove("last_error").remove("last_success").commit()
        TelemetrySync.schedule(context)
    }
    @Synchronized fun connection(context: Context): TelemetryConnection? = runCatching {
        val raw = file(context).readFully(); require(raw.size <= 65536)
        val envelope = JSONObject(raw.toString(Charsets.UTF_8))
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, Base64.decode(envelope.getString("iv"), Base64.NO_WRAP))) }
        val value = JSONObject(cipher.doFinal(Base64.decode(envelope.getString("ciphertext"), Base64.NO_WRAP)).toString(Charsets.UTF_8))
        require(value.getString("endpoint") == endpoint(context))
        TelemetryConnection(endpoint(context), TelemetryCredentials(value.getString("device_id"), value.getString("upload_token"), value.getString("access_client_id"), value.getString("access_client_secret")), revision(context))
    }.getOrNull()
    @Synchronized fun disconnect(context: Context) {
        prefs(context).edit().putBoolean("enabled", false).putLong("revision", revision(context) + 1).remove("last_error").remove("last_success").commit()
        file(context).delete(); TelemetrySync.schedule(context)
    }
    fun status(context: Context) = prefs(context).getString("last_error", "").orEmpty()
    fun lastSuccess(context: Context) = prefs(context).getLong("last_success", 0)
    fun result(context: Context, error: String) {
        prefs(context).edit().putString("last_error", error).apply { if (error.isEmpty()) putLong("last_success", System.currentTimeMillis()) }.apply()
    }
}
