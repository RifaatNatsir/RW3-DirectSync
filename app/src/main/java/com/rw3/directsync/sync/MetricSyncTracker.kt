package com.rw3.directsync.sync

import com.rw3.directsync.ble.parser.XiaomiActivityFileParser
import com.rw3.directsync.ble.parser.XiaomiHeartRateHistoryParser
import com.rw3.directsync.ble.parser.XiaomiSleepFileParser

enum class SyncMetric(val title: String) {
    HEART_RATE("Detak jantung"), SLEEP("Tidur"), STEPS("Langkah"), CALORIES("Kalori"), WORKOUTS("Olahraga");

    companion object {
        val all: Set<SyncMetric> get() = entries.toSet()
        fun forFile(id: ByteArray): Set<SyncMetric> {
            if (XiaomiSleepFileParser.isSleepFile(id)) return setOf(SLEEP)
            if (XiaomiHeartRateHistoryParser.accepts(id)) return setOf(HEART_RATE, STEPS, CALORIES)
            if (!XiaomiActivityFileParser.accepts(id)) return emptySet()
            if (id[6].toInt() and 128 != 0) return setOf(WORKOUTS)
            return if ((id[6].toInt() and 127) shr 2 == 6) setOf(HEART_RATE)
                else setOf(HEART_RATE, STEPS, CALORIES)
        }
    }
}

data class MetricSyncEntry(
    val receivedAt: Long? = null,
    val checkedAt: Long? = null,
    val failures: Map<String, String> = emptyMap(),
    val inProgress: Boolean = false
) {
    val failedFiles: Set<String> get() = failures.keys.filter { it.matches(Regex("[0-9a-f]{14}")) }.toSet()
}

enum class MetricSyncLabel { COMPLETE, UNAVAILABLE, FAILED, SYNCING }

data class SyncMetricEvidence(val dataAt: Long? = null, val value: String = "Belum ada data",
    val complete: Boolean = false, val detail: String = "")

fun metricSyncLabel(entry: MetricSyncEntry, hasData: Boolean): MetricSyncLabel = when {
    entry.inProgress -> MetricSyncLabel.SYNCING
    entry.failures.isNotEmpty() -> MetricSyncLabel.FAILED
    hasData -> MetricSyncLabel.COMPLETE
    else -> MetricSyncLabel.UNAVAILABLE
}

/** A failed list/connection needs discovery; otherwise retry only known failed files in scope. */
fun metricRetryFiles(metrics: Set<SyncMetric>, entries: Map<SyncMetric, MetricSyncEntry>): Set<String> {
    val selected = entries.filterKeys { it in metrics }.values
    if (selected.any { entry -> entry.failures.keys.any { it.startsWith("#") } }) return emptySet()
    return selected.flatMap { it.failedFiles }.filter { hex ->
        val id = hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        SyncMetric.forFile(id).any { it in metrics }
    }.toSet()
}

/** Receipt metadata never stands in for a measurement timestamp or for a missing measurement. */
class MetricSyncTracker(initial: Map<SyncMetric, MetricSyncEntry> = emptyMap()) {
    var entries: Map<SyncMetric, MetricSyncEntry> = SyncMetric.all.associateWith { initial[it] ?: MetricSyncEntry() }
        private set
    var active: Set<SyncMetric> = emptySet()
        private set
    private var fullScan = false
    private val runFailures = mutableSetOf<Pair<SyncMetric, String>>()

    private fun change(metric: SyncMetric, update: (MetricSyncEntry) -> MetricSyncEntry) {
        entries = entries + (metric to update(entries.getValue(metric)))
    }
    fun begin(metrics: Set<SyncMetric>, scan: Boolean = true) {
        require(active.isEmpty()) { "Sinkronisasi masih berjalan" }
        active = metrics.toSet(); fullScan = scan; runFailures.clear()
        active.forEach { change(it) { entry -> entry.copy(inProgress = true) } }
    }
    fun received(fileId: String, family: Set<SyncMetric>, populated: Set<SyncMetric>, now: Long) {
        family.forEach { metric -> change(metric) { entry -> entry.copy(
            receivedAt = if (metric in populated) now else entry.receivedAt,
            failures = entry.failures - fileId)
        } }
    }
    fun receivedExternal(metric: SyncMetric, now: Long, hasData: Boolean = true) {
        change(metric) { it.copy(receivedAt = if (hasData) now else it.receivedAt, checkedAt = now, failures = it.failures - "#import") }
    }
    fun fail(metrics: Set<SyncMetric>, key: String, message: String) {
        metrics.forEach { metric ->
            runFailures.add(metric to key)
            change(metric) { it.copy(failures = (it.failures + (key to message.take(180))).entries.toList().takeLast(64).associate { pair -> pair.toPair() }) }
        }
    }
    fun finish(now: Long) {
        active.forEach { metric -> change(metric) { entry -> entry.copy(inProgress = false, checkedAt = now,
            failures = entry.failures.filterKeys { key -> !fullScan || !key.startsWith("#") || key == "#import" || metric to key in runFailures }) } }
        active = emptySet(); runFailures.clear()
    }
    fun interrupt(message: String) {
        fail(active, "#connection", message)
        cancel()
    }
    fun cancel() {
        active.forEach { change(it) { entry -> entry.copy(inProgress = false) } }
        active = emptySet(); runFailures.clear()
    }
    fun restoreInterrupted() {
        entries.filterValues { it.inProgress }.keys.forEach { metric -> change(metric) {
            it.copy(inProgress = false, failures = it.failures + ("#interrupted" to "Sinkronisasi sebelumnya terhenti. Coba lagi."))
        } }
    }
}
