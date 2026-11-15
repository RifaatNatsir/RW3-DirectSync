package com.rw3.directsync.data

import com.rw3.directsync.data.db.entity.ActivityRecordEntity
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Pure Kotlin mapping, deduplication, and formatting helpers for Mi Fitness data
 * imported via Android Health Connect. Kept free of Android framework imports so
 * all edge cases remain directly unit-testable on the JVM.
 */
object MiFitnessHealthMapper {

    const val HC_SOURCE_PREFIX = "hc:"
    const val HC_FORMAT_VERSION = 100
    private const val START_TOLERANCE_MS = 180_000L
    private const val MAX_ROUTE_POINTS = 250

    data class RoutePoint(val lat: Double, val lon: Double, val altMeters: Double? = null)

    /** Maps Android Health Connect [android.health.connect.datatypes.ExerciseSessionType] constants. */
    fun fromHealthConnectExerciseType(hcType: Int): Int = when (hcType) {
        0 -> 0       // EXERCISE_SESSION_TYPE_UNKNOWN
        1 -> 608     // BADMINTON
        4 -> 6       // BIKING
        5 -> 7       // BIKING_STATIONARY
        8 -> 8       // CALISTHENICS
        20 -> 16     // HIGH_INTENSITY_INTERVAL_TRAINING
        21 -> 5      // HIKING
        27 -> 305    // PILATES
        31, 61 -> 22 // ROWING, ROWING_MACHINE
        33 -> 1      // RUNNING
        34 -> 3      // RUNNING_TREADMILL
        41 -> 20     // SOCCER
        45, 55 -> 308 // STRENGTH_TRAINING, WEIGHTLIFTING
        48, 49 -> 19 // SWIMMING_OPEN_WATER, SWIMMING_POOL
        53 -> 2      // WALKING
        57 -> 12     // YOGA
        58 -> 8      // OTHER_WORKOUT
        60 -> 21     // ELLIPTICAL
        else -> 8
    }

    /** Prefers specific sport types over generic/missing codes when merging records. */
    fun pickBestSportType(primary: Int?, secondary: Int?): Int? {
        if (primary == null || primary == 0) return secondary ?: primary
        if (primary == 8 && secondary != null && secondary != 0 && secondary != 8) return secondary
        return primary
    }

    /** Android Platform SDK [android.health.connect.datatypes.units.Energy.getInCalories] is small calories. */
    fun energyCaloriesToKcal(inCalories: Double?): Int? {
        if (inCalories == null || inCalories <= 0.0 || inCalories.isNaN()) return null
        return (inCalories / 1000.0).roundToInt().takeIf { it > 0 }
    }

    fun downsampleRoute(points: List<RoutePoint>, maxPoints: Int = MAX_ROUTE_POINTS): List<RoutePoint> {
        if (points.size <= maxPoints || maxPoints < 2) return points
        val result = ArrayList<RoutePoint>(maxPoints)
        val lastIdx = points.lastIndex
        for (i in 0 until maxPoints) {
            val idx = ((i.toLong() * lastIdx) / (maxPoints - 1)).toInt()
            result.add(points[idx])
        }
        return result
    }

    fun encodeRoute(points: List<RoutePoint>): String? {
        if (points.size < 2) return null
        val sampled = downsampleRoute(points)
        val sb = StringBuilder(sampled.size * 24)
        sb.append('[')
        sampled.forEachIndexed { idx, p ->
            if (idx > 0) sb.append(',')
            sb.append('[')
            sb.append(String.format(Locale.US, "%.6f", p.lat))
            sb.append(',')
            sb.append(String.format(Locale.US, "%.6f", p.lon))
            p.altMeters?.let { alt ->
                if (!alt.isNaN()) {
                    sb.append(',')
                    sb.append(String.format(Locale.US, "%.1f", alt))
                }
            }
            sb.append(']')
        }
        sb.append(']')
        return sb.toString()
    }

    private val routeTupleRegex = Regex("""\[\s*(-?\d+(?:\.\d+)?)\s*,\s*(-?\d+(?:\.\d+)?)(?:\s*,\s*(-?\d+(?:\.\d+)?))?\s*]""")

    fun decodeRoute(json: String?): List<RoutePoint> {
        if (json.isNullOrBlank()) return emptyList()
        return routeTupleRegex.findAll(json).mapNotNull { m ->
            val lat = m.groupValues[1].toDoubleOrNull() ?: return@mapNotNull null
            val lon = m.groupValues[2].toDoubleOrNull() ?: return@mapNotNull null
            val alt = m.groupValues.getOrNull(3)?.takeIf { it.isNotEmpty() }?.toDoubleOrNull()
            RoutePoint(lat, lon, alt)
        }.toList()
    }

    fun computeSpeedMps(
        distanceMeters: Double?,
        durationSec: Int?,
        speedSamplesMps: List<Double> = emptyList()
    ): Double? {
        val validSamples = speedSamplesMps.filter { it > 0.05 && !it.isNaN() }
        if (validSamples.isNotEmpty()) return validSamples.average()
        if (distanceMeters != null && distanceMeters >= 10.0 && durationSec != null && durationSec > 0) {
            return distanceMeters / durationSec.toDouble()
        }
        return null
    }

    fun computePaceSecPerKm(
        distanceMeters: Double?,
        avgSpeedMps: Double?,
        durationSec: Int?
    ): Int? {
        if (avgSpeedMps != null && avgSpeedMps > 0.1 && !avgSpeedMps.isNaN()) {
            return (1000.0 / avgSpeedMps).roundToInt().takeIf { it in 60..3600 }
        }
        if (distanceMeters != null && distanceMeters >= 10.0 && durationSec != null && durationSec > 0) {
            val pace = (durationSec.toDouble() / (distanceMeters / 1000.0)).roundToInt()
            return pace.takeIf { it in 60..3600 }
        }
        return null
    }

    fun formatPace(paceSecPerKm: Int): String =
        String.format(Locale.US, "%d'%02d\"", paceSecPerKm / 60, paceSecPerKm % 60)

    fun computeCadenceSpm(steps: Int?, durationSec: Int?): Int? {
        if (steps == null || steps <= 0 || durationSec == null || durationSec < 10) return null
        return ((steps.toDouble() * 60.0) / durationSec.toDouble()).roundToInt().takeIf { it > 0 }
    }

    /** Matches an imported workout against an existing watch/HC session within tolerance. */
    fun isSameWorkout(existing: ActivityRecordEntity, candidateStartMs: Long, candidateEndMs: Long?): Boolean {
        if (abs(existing.timestamp - candidateStartMs) > START_TOLERANCE_MS) return false
        val existingEnd = existing.endTimestamp ?: return true
        val candEnd = candidateEndMs ?: return true
        val existingDur = (existingEnd - existing.timestamp).coerceAtLeast(0L)
        val candDur = (candEnd - candidateStartMs).coerceAtLeast(0L)
        val maxDiff = maxOf(START_TOLERANCE_MS, minOf(existingDur, candDur) / 3L)
        return abs(existingDur - candDur) <= maxDiff
    }

    /**
     * Merges [incoming] into [old] without ever discarding populated fields from either source.
     * Watch binary records keep primary ownership when enriched by Health Connect (`hc:*`).
     */
    fun mergeRecord(old: ActivityRecordEntity, incoming: ActivityRecordEntity): ActivityRecordEntity {
        val incomingIsHc = incoming.sourceFileId.startsWith(HC_SOURCE_PREFIX)
        val oldIsWatch = !old.sourceFileId.startsWith(HC_SOURCE_PREFIX)
        val primary = if (incomingIsHc && oldIsWatch) old else incoming
        val secondary = if (incomingIsHc && oldIsWatch) incoming else old

        return old.copy(
            timestamp = if (oldIsWatch && incomingIsHc) old.timestamp else incoming.timestamp,
            endTimestamp = primary.endTimestamp ?: secondary.endTimestamp,
            steps = primary.steps ?: secondary.steps,
            calories = primary.calories ?: secondary.calories,
            restingHeartRate = primary.restingHeartRate ?: secondary.restingHeartRate,
            averageHeartRate = primary.averageHeartRate ?: secondary.averageHeartRate,
            minHeartRate = primary.minHeartRate ?: secondary.minHeartRate,
            maxHeartRate = primary.maxHeartRate ?: secondary.maxHeartRate,
            spo2 = primary.spo2 ?: secondary.spo2,
            stress = primary.stress ?: secondary.stress,
            standingHours = primary.standingHours ?: secondary.standingHours,
            activeSeconds = primary.activeSeconds ?: secondary.activeSeconds,
            sportType = pickBestSportType(primary.sportType, secondary.sportType),
            distanceMeters = primary.distanceMeters ?: secondary.distanceMeters,
            elevationGainMeters = primary.elevationGainMeters ?: secondary.elevationGainMeters,
            avgSpeedMps = primary.avgSpeedMps ?: secondary.avgSpeedMps,
            routeJson = primary.routeJson ?: secondary.routeJson,
            sourceFileId = if (oldIsWatch && incomingIsHc) old.sourceFileId else incoming.sourceFileId,
            formatVersion = if (oldIsWatch && incomingIsHc) old.formatVersion else incoming.formatVersion
        )
    }

    fun mergeDailyTotals(
        old: ActivityRecordEntity?,
        dayStartMs: Long,
        timestampMs: Long,
        steps: Int?,
        calories: Int?,
        sourceFileId: String = "${HC_SOURCE_PREFIX}daily"
    ): ActivityRecordEntity? {
        if (steps == null && calories == null) return null
        if (old == null) {
            return ActivityRecordEntity(
                id = "hc-daily:$dayStartMs",
                category = "daily",
                timestamp = timestampMs,
                steps = steps,
                calories = calories,
                sourceFileId = sourceFileId,
                formatVersion = HC_FORMAT_VERSION
            )
        }
        val bestSteps = listOfNotNull(old.steps, steps).maxOrNull()
        val bestCalories = listOfNotNull(old.calories, calories).maxOrNull()
        return old.copy(
            timestamp = maxOf(old.timestamp, timestampMs),
            steps = bestSteps,
            calories = bestCalories
        )
    }
}
