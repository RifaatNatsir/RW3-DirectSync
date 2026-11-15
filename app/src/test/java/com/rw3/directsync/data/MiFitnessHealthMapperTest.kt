package com.rw3.directsync.data

import com.rw3.directsync.data.MiFitnessHealthMapper.RoutePoint
import com.rw3.directsync.data.db.entity.ActivityRecordEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MiFitnessHealthMapperTest {

    @Test
    fun mapsHealthConnectExerciseTypesToCanonicalInternalTypes() {
        assertEquals(1, MiFitnessHealthMapper.fromHealthConnectExerciseType(33))
        assertEquals(3, MiFitnessHealthMapper.fromHealthConnectExerciseType(34))
        assertEquals(2, MiFitnessHealthMapper.fromHealthConnectExerciseType(53))
        assertEquals(5, MiFitnessHealthMapper.fromHealthConnectExerciseType(21))
        assertEquals(6, MiFitnessHealthMapper.fromHealthConnectExerciseType(4))
        assertEquals(7, MiFitnessHealthMapper.fromHealthConnectExerciseType(5))
        assertEquals(16, MiFitnessHealthMapper.fromHealthConnectExerciseType(20))
        assertEquals(12, MiFitnessHealthMapper.fromHealthConnectExerciseType(57))
        assertEquals(8, MiFitnessHealthMapper.fromHealthConnectExerciseType(9999))

        assertTrue(SportTypeCatalog.matchesFilter(1, SportTypeCatalog.Filter.RUN))
        assertTrue(SportTypeCatalog.matchesFilter(6, SportTypeCatalog.Filter.BIKE))
        assertTrue(SportTypeCatalog.matchesFilter(2, SportTypeCatalog.Filter.WALK))
        assertTrue(SportTypeCatalog.matchesFilter(16, SportTypeCatalog.Filter.GYM))
        assertEquals(308, MiFitnessHealthMapper.fromHealthConnectExerciseType(45))
        assertEquals(608, MiFitnessHealthMapper.fromHealthConnectExerciseType(1))
        assertEquals("Bersepeda Luar", SportTypeCatalog.displayName(6))
    }

    @Test
    fun energyAndDerivedWorkoutMetricsComputeAccuratelyWithoutFabrication() {
        assertEquals(163, MiFitnessHealthMapper.energyCaloriesToKcal(163_000.0))
        assertNull(MiFitnessHealthMapper.energyCaloriesToKcal(0.0))
        assertNull(MiFitnessHealthMapper.energyCaloriesToKcal(null))

        val pace = MiFitnessHealthMapper.computePaceSecPerKm(2200.0, null, 1042)
        assertEquals(474, pace)
        assertEquals("7'54\"", MiFitnessHealthMapper.formatPace(pace!!))
        assertNull(MiFitnessHealthMapper.computePaceSecPerKm(null, null, 1042))

        assertEquals(162, MiFitnessHealthMapper.computeCadenceSpm(2822, 1042))
        assertNull(MiFitnessHealthMapper.computeCadenceSpm(null, 1042))
        assertNull(MiFitnessHealthMapper.computeCadenceSpm(0, 1042))
    }

    @Test
    fun routeEncodeDecodePreservesOrderAndDownsamplesLargeTracks() {
        val raw = (0 until 500).map { i ->
            RoutePoint(-5.20 + i * 0.0001, 119.45 + i * 0.0001, 12.3)
        }
        val encoded = MiFitnessHealthMapper.encodeRoute(raw)
        val decoded = MiFitnessHealthMapper.decodeRoute(encoded)
        assertEquals(250, decoded.size)
        assertEquals(-5.20, decoded.first().lat, 1e-5)
        assertEquals(raw.last().lon, decoded.last().lon, 1e-5)
        assertEquals(12.3, decoded.first().altMeters ?: 0.0, 0.1)

        assertNull(MiFitnessHealthMapper.encodeRoute(listOf(RoutePoint(-5.2, 119.4))))
        assertTrue(MiFitnessHealthMapper.decodeRoute(null).isEmpty())
    }

    @Test
    fun mergePreservesWatchFieldsAndHealthConnectEnrichmentBidirectionally() {
        val watch = ActivityRecordEntity(
            id = "ble-1",
            category = "workout",
            timestamp = 1_700_000_000_000L,
            endTimestamp = 1_700_001_042_000L,
            averageHeartRate = 184,
            maxHeartRate = 195,
            activeSeconds = 1042,
            sportType = 8,
            sourceFileId = "#01020304",
            formatVersion = 5,
            syncedToWebhook = true
        )
        val hc = ActivityRecordEntity(
            id = "ble-1",
            category = "workout",
            timestamp = 1_700_000_020_000L,
            endTimestamp = 1_700_001_042_000L,
            steps = 2822,
            calories = 163,
            sportType = 1,
            distanceMeters = 2200.0,
            elevationGainMeters = 14.0,
            avgSpeedMps = 2.11,
            routeJson = "[[-5.2,119.45],[-5.21,119.46]]",
            sourceFileId = "hc:workout",
            formatVersion = 100
        )

        val enriched = MiFitnessHealthMapper.mergeRecord(watch, hc)
        assertEquals("#01020304", enriched.sourceFileId)
        assertEquals(5, enriched.formatVersion)
        assertEquals(1, enriched.sportType)
        assertEquals(184, enriched.averageHeartRate)
        assertEquals(2822, enriched.steps)
        assertEquals(163, enriched.calories)
        assertEquals(2200.0, enriched.distanceMeters!!, 0.1)
        assertTrue(!enriched.routeJson.isNullOrEmpty())

        // Later BLE re-download with nulls must not wipe imported distance/route/steps
        val reDownloadedWatch = watch.copy(sportType = 8, distanceMeters = null, routeJson = null, steps = null)
        val afterRedownload = MiFitnessHealthMapper.mergeRecord(enriched, reDownloadedWatch)
        assertEquals(2200.0, afterRedownload.distanceMeters!!, 0.1)
        assertEquals(enriched.routeJson, afterRedownload.routeJson)
        assertEquals(2822, afterRedownload.steps)
        assertEquals(1, afterRedownload.sportType)
    }

    @Test
    fun deduplicationAndDailyMergeMatchWithoutDuplicatingSessions() {
        val watch = ActivityRecordEntity(
            id = "w1",
            category = "workout",
            timestamp = 1_700_000_000_000L,
            endTimestamp = 1_700_001_042_000L,
            sourceFileId = "#01",
            formatVersion = 5
        )
        assertTrue(MiFitnessHealthMapper.isSameWorkout(watch, 1_700_000_090_000L, 1_700_001_100_000L))
        assertFalse(MiFitnessHealthMapper.isSameWorkout(watch, 1_700_000_400_000L, 1_700_001_400_000L))

        val existingDaily = ActivityRecordEntity(
            id = "daily-live:1700000000000",
            category = "daily",
            timestamp = 1_700_040_000_000L,
            steps = 5000,
            calories = 210,
            sourceFileId = "realtime",
            formatVersion = 0
        )
        val mergedDaily = MiFitnessHealthMapper.mergeDailyTotals(
            existingDaily, 1_700_000_000_000L, 1_700_030_000_000L, 4200, 260
        )!!
        assertEquals("daily-live:1700000000000", mergedDaily.id)
        assertEquals(5000, mergedDaily.steps)
        assertEquals(260, mergedDaily.calories)
        assertEquals(1_700_040_000_000L, mergedDaily.timestamp)
    }
}
