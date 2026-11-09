package com.rw3.directsync.ble.parser

import com.rw3.directsync.sync.*
import org.junit.Assert.*
import org.junit.Test

class MetricSyncTrackerTest {
    private val a = "01000000200420"
    private val b = "02000000200420"
    private val sleep = setOf(SyncMetric.SLEEP)

    @Test fun emptySuccessfulScanIsUnavailableAndDoesNotInventReceiptTime() {
        val tracker = MetricSyncTracker()
        tracker.begin(SyncMetric.all); tracker.finish(100)
        val entry = tracker.entries.getValue(SyncMetric.STEPS)
        assertEquals(100L, entry.checkedAt); assertNull(entry.receivedAt)
        assertEquals(MetricSyncLabel.UNAVAILABLE, metricSyncLabel(entry, false))
        assertEquals(MetricSyncLabel.COMPLETE, metricSyncLabel(entry, true)) // Includes an actual zero total.
    }
    @Test fun anotherSleepFileCannotHideAFailedDownload() {
        val tracker = MetricSyncTracker()
        tracker.begin(sleep); tracker.fail(sleep, a, "Terpotong")
        tracker.received(b, sleep, sleep, 100); tracker.finish(110)
        val entry = tracker.entries.getValue(SyncMetric.SLEEP)
        assertEquals(100L, entry.receivedAt); assertEquals(setOf(a), entry.failedFiles)
        assertEquals(MetricSyncLabel.FAILED, metricSyncLabel(entry, true))
        tracker.begin(sleep, scan = false); tracker.received(a, sleep, sleep, 120); tracker.finish(130)
        assertEquals(MetricSyncLabel.COMPLETE, metricSyncLabel(tracker.entries.getValue(SyncMetric.SLEEP), true))
    }
    @Test fun retryOfOneMetricDoesNotStartOrClearAnotherMetric() {
        val tracker = MetricSyncTracker()
        tracker.fail(setOf(SyncMetric.WORKOUTS), "030000002007a1", "Timeout")
        tracker.begin(setOf(SyncMetric.HEART_RATE))
        assertTrue(tracker.entries.getValue(SyncMetric.HEART_RATE).inProgress)
        assertFalse(tracker.entries.getValue(SyncMetric.WORKOUTS).inProgress)
        tracker.finish(50)
        assertEquals(MetricSyncLabel.FAILED, metricSyncLabel(tracker.entries.getValue(SyncMetric.WORKOUTS), true))
    }
    @Test fun sharedDailyFileClearsItsOwnFailureButOnlyPresentValuesGetReceiptTimes() {
        val tracker = MetricSyncTracker()
        val daily = setOf(SyncMetric.HEART_RATE, SyncMetric.STEPS, SyncMetric.CALORIES)
        tracker.fail(daily, "01000000200300", "CRC")
        tracker.received("01000000200300", daily, setOf(SyncMetric.STEPS), 70)
        assertEquals(70L, tracker.entries.getValue(SyncMetric.STEPS).receivedAt)
        assertNull(tracker.entries.getValue(SyncMetric.CALORIES).receivedAt)
        assertTrue(tracker.entries.getValue(SyncMetric.CALORIES).failures.isEmpty())
    }
    @Test fun failedDiscoverySurvivesAnOtherwiseSuccessfulFileUntilNextFullScan() {
        val tracker = MetricSyncTracker()
        tracker.begin(sleep); tracker.fail(sleep, "#discovery", "Daftar tidak dijawab")
        tracker.received(a, sleep, sleep, 60); tracker.finish(70)
        assertTrue(tracker.entries.getValue(SyncMetric.SLEEP).failures.containsKey("#discovery"))
        tracker.begin(sleep); tracker.finish(80)
        assertTrue(tracker.entries.getValue(SyncMetric.SLEEP).failures.isEmpty())
    }
    @Test fun disconnectionMarksOnlyActiveMetricsAndRetainsLastGoodReceipt() {
        val tracker = MetricSyncTracker()
        tracker.received(a, sleep, sleep, 50); tracker.begin(sleep)
        tracker.interrupt("Koneksi terputus")
        assertEquals(50L, tracker.entries.getValue(SyncMetric.SLEEP).receivedAt)
        assertFalse(tracker.entries.getValue(SyncMetric.SLEEP).inProgress)
        assertTrue(tracker.entries.getValue(SyncMetric.STEPS).failures.isEmpty())
    }
    @Test fun recreationMarksUnfinishedSyncAsInterruptedWithoutChangingMeasurementMetadata() {
        val tracker = MetricSyncTracker(mapOf(SyncMetric.SLEEP to MetricSyncEntry(50, 40, inProgress = true),
            SyncMetric.HEART_RATE to MetricSyncEntry(30, 30)))
        tracker.restoreInterrupted()
        assertEquals(50L, tracker.entries.getValue(SyncMetric.SLEEP).receivedAt)
        assertTrue(tracker.entries.getValue(SyncMetric.SLEEP).failures.containsKey("#interrupted"))
        assertTrue(tracker.entries.getValue(SyncMetric.HEART_RATE).failures.isEmpty())
    }
    @Test fun userPauseCancelsProgressWithoutInventingAConnectionFailure() {
        val tracker = MetricSyncTracker()
        tracker.begin(SyncMetric.all); tracker.cancel()
        assertTrue(tracker.active.isEmpty())
        assertTrue(tracker.entries.values.none { it.inProgress || it.failures.isNotEmpty() })
    }
    @Test fun emptyExternalImportClearsOnlyItsErrorAndDoesNotInventAReceivedRecord() {
        val tracker = MetricSyncTracker()
        tracker.fail(sleep, "#import", "Izin belum ada"); tracker.fail(sleep, a, "CRC")
        tracker.receivedExternal(SyncMetric.SLEEP, 90, hasData = false)
        assertNull(tracker.entries.getValue(SyncMetric.SLEEP).receivedAt)
        assertEquals(setOf(a), tracker.entries.getValue(SyncMetric.SLEEP).failures.keys)
    }
    @Test fun categoriesFollowActualFileTypesAndIgnoreUnsupportedWorkoutDetails() {
        fun id(flag: Int) = byteArrayOf(1, 0, 0, 0, 32, 4, flag.toByte())
        assertEquals(sleep, SyncMetric.forFile(id(0x20)))
        assertEquals(setOf(SyncMetric.WORKOUTS), SyncMetric.forFile(id(0xa1)))
        assertTrue(SyncMetric.forFile(id(0xa0)).isEmpty())
        assertEquals(setOf(SyncMetric.HEART_RATE, SyncMetric.STEPS, SyncMetric.CALORIES), SyncMetric.forFile(id(0)))
        assertTrue(SyncMetric.forFile(byteArrayOf(1)).isEmpty())
    }
    @Test fun retryPlanOnlyRequestsFailedFilesForSelectedMetric() {
        val entries = mapOf(SyncMetric.SLEEP to MetricSyncEntry(failures = mapOf(a to "CRC")),
            SyncMetric.WORKOUTS to MetricSyncEntry(failures = mapOf("030000002007a1" to "Timeout")))
        assertEquals(setOf(a), metricRetryFiles(sleep, entries))
        assertEquals(setOf("030000002007a1"), metricRetryFiles(setOf(SyncMetric.WORKOUTS), entries))
    }
    @Test fun retryPlanFallsBackToDiscoveryWhenFileIdentityIsUnknown() {
        val entries = mapOf(SyncMetric.SLEEP to MetricSyncEntry(failures = mapOf(a to "CRC", "#connection" to "Terputus")))
        assertTrue(metricRetryFiles(sleep, entries).isEmpty())
    }
    @Test fun retryPlanRejectsAnUnsupportedOrWrongCategoryIdentity() {
        val entries = mapOf(SyncMetric.SLEEP to MetricSyncEntry(failures = mapOf("030000002007a1" to "Wrong category", "010000002001a0" to "Unsupported")))
        assertTrue(metricRetryFiles(sleep, entries).isEmpty())
    }
}
