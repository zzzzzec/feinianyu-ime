// SPDX-License-Identifier: GPL-3.0-or-later
package org.fcitx.fcitx5.android

import android.text.InputType
import android.content.Intent
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import android.view.inputmethod.EditorInfo
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import org.fcitx.fcitx5.android.dataset.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.io.IOException
import java.util.UUID

class TelemetryTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun fixture(endpoint: String = "https://telemetry.invalid", device: String = "11111111-1111-4111-8111-111111111111") = JSONObject()
        .put("schema", 1).put("server_url", endpoint).put("device_id", device).put("upload_token", "synthetic-test-token-" + "x".repeat(32)).toString()
    private fun seed(type: String) {
        InputDataset.initialize(context); InputDataset.setEnabled(context, true)
        val editor = EditorInfo().apply { inputType = InputType.TYPE_CLASS_TEXT; packageName = "dev.feinianyu.synthetic.telemetry" }
        InputDataset.session(context, editor)
        InputDataset.event(type, JSONObject().put("synthetic", true))
        InputDataset.setEnabled(context, false)
    }
    private fun ack(config: TelemetryConnection, batch: TelemetryBatch) = JSONObject().put("schema", 1).put("status", "stored")
        .put("device_id", config.credentials.deviceId).put("generation", batch.generation).put("batch_id", batch.id).put("sha256", batch.sha256)
        .put("first_seq", batch.first).put("last_seq", batch.last).put("event_count", batch.count).put("bytes", batch.body.size)
    @Test fun editableAddressUi(): Unit = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val activity = instrumentation.startActivitySync(Intent(context, InputDatasetActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        val automation = instrumentation.uiAutomation
        fun flatten(node: AccessibilityNodeInfo): List<AccessibilityNodeInfo> = listOf(node) + (0 until node.childCount).mapNotNull { node.getChild(it) }.flatMap { flatten(it) }
        suspend fun nodes(): List<AccessibilityNodeInfo> { withTimeout(5000) { while (automation.rootInActiveWindow == null) delay(50) }; return flatten(automation.rootInActiveWindow) }
        suspend fun change(value: String) {
            val button = nodes().first { it.text?.toString() == context.getString(R.string.telemetry_address) && it.isClickable }
            assertTrue(button.performAction(AccessibilityNodeInfo.ACTION_CLICK)); delay(200)
            val field = nodes().first { it.className?.toString() == "android.widget.EditText" }
            assertTrue(field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value) }))
            val confirm = nodes().first { it.text?.toString() == context.getString(android.R.string.ok) && it.isClickable }
            assertTrue(confirm.performAction(AccessibilityNodeInfo.ACTION_CLICK))
            withTimeout(5000) { while (TelemetrySettings.endpoint(context) != value) delay(50) }
        }
        try {
            delay(500); change("https://example.com/ime")
            assertNull(TelemetrySettings.connection(context)); assertFalse(TelemetrySettings.enabled(context))
            change(TelemetrySettings.DEFAULT_ENDPOINT)
        } finally { instrumentation.runOnMainSync { activity.finish() } }
    }
    @Test fun durableQueueAndPairing(): Unit = runBlocking {
        InputDataset.initialize(context); TelemetrySettings.disconnect(context); InputDataset.clear()
        TelemetrySettings.setPerformance(context, false)
        assertNull(TelemetrySettings.connection(context)); assertFalse(TelemetrySettings.enabled(context))
        TelemetrySettings.importPairing(context, fixture())
        val config = checkNotNull(TelemetrySettings.connection(context))
        assertEquals("https://telemetry.invalid", config.endpoint)
        val encrypted = File(context.noBackupFilesDir, "telemetry/credentials.bin").readText()
        assertFalse(encrypted.contains(config.credentials.uploadToken))
        assertFalse(config.toString().contains(config.credentials.uploadToken))
        seed("synthetic_durable")
        val total = InputDataset.stats().first; assertTrue(total > 0)
        val first = checkNotNull(InputDataset.prepareBatch(config.destination))
        val retried = checkNotNull(InputDataset.prepareBatch(config.destination))
        assertEquals(first.id, retried.id); assertArrayEquals(first.body, retried.body)
        TelemetrySettings.setEnabled(context, true)
        assertTrue(TelemetrySync.syncWith(context, TelemetryTransport { _, _ -> throw IOException("synthetic lost receipt") }, false))
        assertEquals(total, InputDataset.pending(config.destination).count)
        assertEquals(first.id, checkNotNull(InputDataset.prepareBatch(config.destination)).id)
        TelemetrySync.syncWith(context, TelemetryTransport { cfg, batch -> ack(cfg, batch).put("sha256", "0".repeat(64)) }, false)
        assertEquals("receipt_mismatch", TelemetrySettings.status(context))
        assertEquals(total, InputDataset.pending(config.destination).count)
        TelemetrySync.syncWith(context, TelemetryTransport { cfg, batch -> ack(cfg, batch) }, false)
        assertEquals(0L, InputDataset.pending(config.destination).count)
        assertEquals(total, InputDataset.stats().first) // Upload ACK does not delete the local dataset.
        assertNull(InputDataset.prepareBatch(config.destination))
        TelemetrySettings.setEndpoint(context, "https://different.invalid/api/")
        assertEquals("https://different.invalid/api", TelemetrySettings.endpoint(context))
        assertNull(TelemetrySettings.connection(context)); assertFalse(TelemetrySettings.enabled(context))
        assertEquals(total, InputDataset.stats().first)
        for (bad in listOf("http://example.com", "https://user:pass@example.com", "https://example.com?token=bad", "https://example.com/#fragment")) {
            assertTrue(runCatching { TelemetrySettings.normalizeEndpoint(bad) }.isFailure)
        }
        TelemetrySettings.importPairing(context, fixture())
        seed("synthetic_before_clear")
        val stale = checkNotNull(InputDataset.prepareBatch(config.destination))
        InputDataset.clear()
        assertFalse(InputDataset.acknowledge(config.destination, stale))
        seed("synthetic_after_clear")
        val next = checkNotNull(InputDataset.prepareBatch(config.destination)); assertNotEquals(stale.generation, next.generation)
        File(context.getExternalFilesDir(null), "telemetry-restart-marker.json").writeText(JSONObject().put("id", next.id).put("sha256", next.sha256).toString())
        TelemetrySettings.setEnabled(context, false)
    }
    @Test fun persistedCredentialsAndBatch(): Unit = runBlocking {
        InputDataset.initialize(context)
        val config = checkNotNull(TelemetrySettings.connection(context))
        val marker = JSONObject(File(context.getExternalFilesDir(null), "telemetry-restart-marker.json").readText())
        val batch = checkNotNull(InputDataset.prepareBatch(config.destination))
        assertEquals(marker.getString("id"), batch.id); assertEquals(marker.getString("sha256"), batch.sha256)
        TelemetrySettings.setEnabled(context, true)
        TelemetrySync.syncWith(context, TelemetryTransport { cfg, value -> ack(cfg, value) }, false)
        assertEquals(0L, InputDataset.pending(config.destination).count)
        TelemetrySettings.disconnect(context); InputDataset.clear()
    }
    @Test fun liveCloudUpload(): Unit = runBlocking {
        InputDataset.initialize(context); TelemetrySettings.disconnect(context); InputDataset.clear()
        val profile = File(context.getExternalFilesDir(null), "telemetry-live-pair.json")
        TelemetrySettings.importPairing(context, profile.readText()); profile.delete()
        TelemetrySettings.setConstraints(context, false, false); TelemetrySettings.setPerformance(context, false)
        TelemetrySync.checkConnection(context)
        seed("synthetic_android_cloud_upload")
        val config = checkNotNull(TelemetrySettings.connection(context))
        val batch = checkNotNull(InputDataset.prepareBatch(config.destination))
        val report = JSONObject().put("device_id", config.credentials.deviceId).put("generation", batch.generation).put("batch_id", batch.id)
            .put("sha256", batch.sha256).put("bytes", batch.body.size).put("count", batch.count)
        File(context.getExternalFilesDir(null), "telemetry-live-body.jsonl.gz").writeBytes(batch.body)
        TelemetrySettings.setEnabled(context, true)
        TelemetrySync.sync(context)
        assertEquals("", TelemetrySettings.status(context)); assertEquals(0L, InputDataset.pending(config.destination).count)
        seed("synthetic_android_after_restart")
        val pending = checkNotNull(InputDataset.prepareBatch(config.destination))
        report.put("pending_id", pending.id).put("pending_sha256", pending.sha256)
        File(context.getExternalFilesDir(null), "telemetry-live-report.json").writeText(report.toString())
        TelemetrySettings.setEnabled(context, false)
    }
    @Test fun liveCloudAfterRestart(): Unit = runBlocking {
        InputDataset.initialize(context)
        val config = checkNotNull(TelemetrySettings.connection(context))
        val report = JSONObject(File(context.getExternalFilesDir(null), "telemetry-live-report.json").readText())
        val pending = checkNotNull(InputDataset.prepareBatch(config.destination))
        assertEquals(report.getString("pending_id"), pending.id); assertEquals(report.getString("pending_sha256"), pending.sha256)
        TelemetrySync.checkConnection(context); TelemetrySettings.setEnabled(context, true); TelemetrySync.sync(context)
        assertEquals("", TelemetrySettings.status(context)); assertEquals(0L, InputDataset.pending(config.destination).count)
        TelemetrySettings.disconnect(context); InputDataset.clear()
    }
}
