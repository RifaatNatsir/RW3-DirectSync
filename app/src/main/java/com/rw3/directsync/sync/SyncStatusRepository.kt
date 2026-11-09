package com.rw3.directsync.sync

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject

class SyncStatusRepository private constructor(context: Context) {
    private val prefs = context.getSharedPreferences("metric_sync_status", Context.MODE_PRIVATE)
    private val tracker = MetricSyncTracker(SyncMetric.all.associateWith { metric ->
        runCatching {
            val json = JSONObject(prefs.getString(metric.name, "{}")!!)
            val errors = json.optJSONObject("failures") ?: JSONObject()
            MetricSyncEntry(json.optLong("receivedAt").takeIf { it > 0 }, json.optLong("checkedAt").takeIf { it > 0 },
                errors.keys().asSequence().associateWith { errors.getString(it) }, json.optBoolean("inProgress"))
        }.getOrDefault(MetricSyncEntry())
    }).apply { restoreInterrupted() }
    private val _entries = MutableStateFlow(tracker.entries)
    val entries = _entries.asStateFlow()
    @Synchronized fun active(): Set<SyncMetric> = tracker.active.toSet()
    @Synchronized fun begin(metrics: Set<SyncMetric>, fullScan: Boolean = true) { tracker.begin(metrics, fullScan); publish() }
    @Synchronized fun received(id: ByteArray, metrics: Set<SyncMetric>) {
        tracker.received(id.hex(), SyncMetric.forFile(id), metrics, System.currentTimeMillis()); publish()
    }
    @Synchronized fun importedSleep(hasRecords: Boolean) {
        tracker.receivedExternal(SyncMetric.SLEEP, System.currentTimeMillis(), hasRecords)
        publish()
    }
    @Synchronized fun observed(metrics: Set<SyncMetric>) {
        metrics.forEach { tracker.receivedExternal(it, System.currentTimeMillis()) }; publish()
    }
    @Synchronized fun importFailed(message: String) { tracker.fail(setOf(SyncMetric.SLEEP), "#import", message); publish() }
    @Synchronized fun fileFailed(id: ByteArray, message: String) {
        tracker.fail(SyncMetric.forFile(id), id.hex(), message); publish()
    }
    @Synchronized fun discoveryFailed(message: String) { tracker.fail(tracker.active, "#discovery", message); publish() }
    @Synchronized fun finish() { tracker.finish(System.currentTimeMillis()); publish() }
    @Synchronized fun interrupt(message: String) { tracker.interrupt(message); publish() }
    @Synchronized fun cancel() { tracker.cancel(); publish() }
    private fun publish() {
        _entries.value = tracker.entries
        prefs.edit().apply {
            tracker.entries.forEach { (metric, entry) -> putString(metric.name, JSONObject().apply {
                put("receivedAt", entry.receivedAt ?: 0); put("checkedAt", entry.checkedAt ?: 0)
                put("inProgress", entry.inProgress); put("failures", JSONObject(entry.failures))
            }.toString()) }
        }.apply()
    }
    companion object {
        @Volatile private var instance: SyncStatusRepository? = null
        fun get(context: Context): SyncStatusRepository = instance ?: synchronized(this) {
            instance ?: SyncStatusRepository(context.applicationContext).also { instance = it }
        }
        fun ByteArray.hex() = joinToString("") { "%02x".format(it.toInt() and 255) }
        fun fileId(hex: String): ByteArray? = hex.takeIf { it.matches(Regex("[0-9a-f]{14}")) }
            ?.chunked(2)?.map { it.toInt(16).toByte() }?.toByteArray()
    }
}
