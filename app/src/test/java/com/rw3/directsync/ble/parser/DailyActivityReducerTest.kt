package com.rw3.directsync.ble.parser

import com.rw3.directsync.data.DailyActivityReducer
import com.rw3.directsync.data.db.entity.ActivityRecordEntity
import com.rw3.directsync.data.db.entity.DailyActivityMinuteEntity
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.*
import org.junit.Test

class DailyActivityReducerTest {
    private val zone = ZoneId.of("Asia/Makassar")
    private val start = LocalDate.of(2026, 11, 8).atStartOfDay(zone).toInstant().toEpochMilli()
    private fun snapshot(offset: Long, steps: Int?, calories: Int?) = ActivityRecordEntity(
        id = "snapshot:$offset", category = "daily", timestamp = start + offset,
        steps = steps, calories = calories, sourceFileId = "test", formatVersion = 3)
    private fun minute(n: Int, steps: Int?, calories: Int?) = DailyActivityMinuteEntity(start + n * 60_000L, steps, calories, "test")

    @Test fun partialFileAndZeroRealtimeDoNotEraseEstablishedDailyCounters() {
        val result = DailyActivityReducer.merge(listOf(snapshot(1000, 630, 42),
            snapshot(2000, 0, 0), snapshot(3000, 0, 5)), emptyList(), zone)!!
        assertEquals(630, result.steps); assertEquals(42, result.calories)
    }
    @Test fun overlappingMinuteFilesCountEachMinuteOnce() {
        val points = listOf(minute(0, 10, 1), minute(1, 15, 2), minute(1, 15, 2), minute(2, 20, 3))
        val result = DailyActivityReducer.merge(listOf(snapshot(200_000, 0, 3)), points, zone)!!
        assertEquals(45, result.steps); assertEquals(6, result.calories)
    }
    @Test fun cumulativeSnapshotsAreNotAddedToMinuteTotals() {
        val result = DailyActivityReducer.merge(listOf(snapshot(200_000, 100, 20)),
            listOf(minute(0, 10, 1), minute(1, 20, 2)), zone)!!
        assertEquals(100, result.steps); assertEquals(20, result.calories)
    }
    @Test fun missingNewMetricKeepsEarlierReportedValue() {
        val result = DailyActivityReducer.merge(listOf(snapshot(1000, 630, 42), snapshot(2000, null, 45)), emptyList(), zone)!!
        assertEquals(630, result.steps); assertEquals(45, result.calories)
        val unknown = DailyActivityReducer.merge(listOf(snapshot(1000, null, null)), emptyList(), zone)!!
        assertNull(unknown.steps); assertNull(unknown.calories)
    }
    @Test fun dayBoundaryResetsCountersAndDoesNotCarryYesterdayForward() {
        val yesterday = snapshot(-60_000, 9000, 400)
        val today = snapshot(60_000, 0, 0)
        val result = DailyActivityReducer.merge(listOf(yesterday, today), listOf(minute(-1, 50, 5)), zone)!!
        assertEquals(0, result.steps); assertEquals(0, result.calories)
    }
}
