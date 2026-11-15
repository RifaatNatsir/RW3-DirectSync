package com.rw3.directsync.data

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.health.connect.AggregateRecordsGroupedByPeriodResponse
import android.health.connect.AggregateRecordsRequest
import android.health.connect.AggregateRecordsResponse
import android.health.connect.HealthConnectException
import android.health.connect.HealthConnectManager
import android.health.connect.LocalTimeRangeFilter
import android.health.connect.ReadRecordsRequestUsingFilters
import android.health.connect.ReadRecordsResponse
import android.health.connect.TimeInstantRangeFilter
import android.health.connect.TimeRangeFilter
import android.health.connect.datatypes.ActiveCaloriesBurnedRecord
import android.health.connect.datatypes.AggregationType
import android.health.connect.datatypes.DataOrigin
import android.health.connect.datatypes.DistanceRecord
import android.health.connect.datatypes.ElevationGainedRecord
import android.health.connect.datatypes.ExerciseSessionRecord
import android.health.connect.datatypes.HeartRateRecord
import android.health.connect.datatypes.OxygenSaturationRecord
import android.health.connect.datatypes.Record
import android.health.connect.datatypes.SleepSessionRecord
import android.health.connect.datatypes.SpeedRecord
import android.health.connect.datatypes.StepsRecord
import android.os.Build
import android.os.OutcomeReceiver
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import com.rw3.directsync.data.MiFitnessHealthMapper.RoutePoint
import com.rw3.directsync.data.db.Rw3Database
import com.rw3.directsync.data.db.entity.ActivityRecordEntity
import com.rw3.directsync.data.db.entity.HeartRateEntity
import com.rw3.directsync.sync.SyncMetric
import com.rw3.directsync.sync.SyncStatusRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asExecutor
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.Period
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.roundToInt

/**
 * Reads Mi Fitness (`com.xiaomi.wearable`) historical and recent health records from
 * Android Health Connect into RW3-DirectSync's local Room database.
 * Strictly read-only: never writes, modifies, or deletes Health Connect records.
 */
@RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
class MiFitnessHealthConnectImporter(private val context: Context) {

    data class ImportSummary(
        val sleepSessions: Int = 0,
        val heartRateSamples: Int = 0,
        val dailyDays: Int = 0,
        val workouts: Int = 0,
        val spo2Records: Int = 0,
        val routesImported: Int = 0,
        val routesPendingPermission: Int = 0,
        val grantedTypes: Int = 0,
        val hasFullHistory: Boolean = false
    ) {
        val totalItems: Int
            get() = sleepSessions + heartRateSamples + dailyDays + workouts + spo2Records
    }

    companion object {
        private const val TAG = "MiFitnessHcImporter"
        const val MI_FITNESS_PACKAGE = "com.xiaomi.wearable"

        const val READ_SLEEP = "android.permission.health.READ_SLEEP"
        const val READ_HEART_RATE = "android.permission.health.READ_HEART_RATE"
        const val READ_STEPS = "android.permission.health.READ_STEPS"
        const val READ_CALORIES = "android.permission.health.READ_ACTIVE_CALORIES_BURNED"
        const val READ_EXERCISE = "android.permission.health.READ_EXERCISE"
        const val READ_DISTANCE = "android.permission.health.READ_DISTANCE"
        const val READ_SPEED = "android.permission.health.READ_SPEED"
        const val READ_ELEVATION = "android.permission.health.READ_ELEVATION_GAINED"
        const val READ_SPO2 = "android.permission.health.READ_OXYGEN_SATURATION"
        const val READ_EXERCISE_ROUTES = "android.permission.health.READ_EXERCISE_ROUTES"
        const val READ_HISTORY = "android.permission.health.READ_HEALTH_DATA_HISTORY"

        private val CORE_PERMISSIONS = listOf(
            READ_SLEEP,
            READ_HEART_RATE,
            READ_STEPS,
            READ_CALORIES,
            READ_EXERCISE,
            READ_DISTANCE,
            READ_SPEED,
            READ_ELEVATION,
            READ_SPO2
        )

        private val ALL_PERMISSIONS = CORE_PERMISSIONS + listOf(
            READ_EXERCISE_ROUTES,
            READ_HISTORY
        )

        fun isAvailable(context: Context): Boolean =
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
                context.getSystemService(HealthConnectManager::class.java) != null

        fun supportedPermissions(context: Context): List<String> =
            ALL_PERMISSIONS.filter { perm ->
                runCatching { context.packageManager.getPermissionInfo(perm, 0) != null }
                    .getOrDefault(false)
            }

        fun hasPermission(context: Context, permission: String): Boolean =
            ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

        fun missingPermissions(context: Context): List<String> =
            supportedPermissions(context).filterNot { hasPermission(context, it) }

        fun hasAnyReadPermission(context: Context): Boolean =
            CORE_PERMISSIONS.any { hasPermission(context, it) }

        fun managePermissionsIntent(context: Context): Intent =
            Intent(HealthConnectManager.ACTION_MANAGE_HEALTH_PERMISSIONS).apply {
                putExtra(Intent.EXTRA_PACKAGE_NAME, context.packageName)
            }

        fun healthHomeSettingsIntent(): Intent =
            Intent("android.health.connect.action.HEALTH_HOME_SETTINGS")
    }

    private val miOrigin: DataOrigin = DataOrigin.Builder()
        .setPackageName(MI_FITNESS_PACKAGE)
        .build()

    private val callbackExecutor = Dispatchers.IO.asExecutor()

    suspend fun importAll(): ImportSummary = withContext(Dispatchers.IO) {
        val manager = requireNotNull(context.getSystemService(HealthConnectManager::class.java)) {
            "Health Connect tidak tersedia di perangkat ini"
        }
        val db = Rw3Database.getDatabase(context)
        val syncRepo = SyncStatusRepository.get(context)
        val hasHistoryPerm = hasPermission(context, READ_HISTORY)
        val now = Instant.now()
        val fullStart = if (hasHistoryPerm) {
            Instant.parse("2018-01-01T00:00:00Z")
        } else {
            now.minus(30, ChronoUnit.DAYS)
        }
        val fallbackStart = now.minus(30, ChronoUnit.DAYS)

        var sleepCount = 0
        var hrCount = 0
        var dailyCount = 0
        var workoutResult = WorkoutImportStats()
        var spo2Count = 0
        var grantedCount = 0

        if (hasPermission(context, READ_SLEEP)) {
            grantedCount++
            runCatching { importSleep(manager, db, fullStart, fallbackStart, now) }
                .onSuccess {
                    sleepCount = it
                    syncRepo.imported(setOf(SyncMetric.SLEEP), it > 0)
                }
                .onFailure {
                    Log.e(TAG, "Sleep import failed", it)
                    syncRepo.importFailed(setOf(SyncMetric.SLEEP), it.message ?: "Gagal impor tidur")
                }
        }

        if (hasPermission(context, READ_HEART_RATE)) {
            grantedCount++
            runCatching { importHeartRate(manager, db, fullStart, fallbackStart, now) }
                .onSuccess {
                    hrCount = it
                    syncRepo.imported(setOf(SyncMetric.HEART_RATE), it > 0)
                }
                .onFailure {
                    Log.e(TAG, "HR import failed", it)
                    syncRepo.importFailed(setOf(SyncMetric.HEART_RATE), it.message ?: "Gagal impor detak jantung")
                }
        }

        val canSteps = hasPermission(context, READ_STEPS)
        val canCalories = hasPermission(context, READ_CALORIES)
        if (canSteps) grantedCount++
        if (canCalories) grantedCount++
        if (canSteps || canCalories) {
            val metrics = buildSet {
                if (canSteps) add(SyncMetric.STEPS)
                if (canCalories) add(SyncMetric.CALORIES)
            }
            runCatching { importDailyActivity(manager, db, hasHistoryPerm, canSteps, canCalories) }
                .onSuccess {
                    dailyCount = it
                    syncRepo.imported(metrics, it > 0)
                }
                .onFailure {
                    Log.e(TAG, "Daily steps/calories import failed", it)
                    syncRepo.importFailed(metrics, it.message ?: "Gagal impor aktivitas harian")
                }
        }

        if (hasPermission(context, READ_SPO2)) {
            grantedCount++
            runCatching { importSpo2(manager, db, fullStart, fallbackStart, now) }
                .onSuccess { spo2Count = it }
                .onFailure { Log.w(TAG, "SpO2 import failed", it) }
        }

        if (hasPermission(context, READ_EXERCISE)) {
            grantedCount++
            runCatching { importWorkouts(manager, db, fullStart, fallbackStart, now) }
                .onSuccess {
                    workoutResult = it
                    syncRepo.imported(setOf(SyncMetric.WORKOUTS), it.workouts > 0)
                }
                .onFailure {
                    Log.e(TAG, "Workouts import failed", it)
                    syncRepo.importFailed(setOf(SyncMetric.WORKOUTS), it.message ?: "Gagal impor olahraga")
                }
        }

        ImportSummary(
            sleepSessions = sleepCount,
            heartRateSamples = hrCount,
            dailyDays = dailyCount,
            workouts = workoutResult.workouts,
            spo2Records = spo2Count,
            routesImported = workoutResult.routesImported,
            routesPendingPermission = workoutResult.routesPendingPermission,
            grantedTypes = grantedCount,
            hasFullHistory = hasHistoryPerm
        )
    }

    private data class WorkoutImportStats(
        val workouts: Int = 0,
        val routesImported: Int = 0,
        val routesPendingPermission: Int = 0
    )

    private suspend fun importSleep(
        manager: HealthConnectManager,
        db: Rw3Database,
        preferredStart: Instant,
        fallbackStart: Instant,
        end: Instant
    ): Int {
        var count = 0
        readAllPagesWithFallback(manager, SleepSessionRecord::class.java, preferredStart, fallbackStart, end) { page ->
            for (record in page) {
                runCatching {
                    val source = "healthconnect:$MI_FITNESS_PACKAGE:${record.metadata.id}"
                    val parsed = SleepHistoryMapper.map(
                        record.startTime.toEpochMilli(),
                        record.endTime.toEpochMilli(),
                        source,
                        record.stages.map {
                            SleepHistoryMapper.Stage(it.startTime.toEpochMilli(), it.endTime.toEpochMilli(), it.type)
                        }
                    )
                    db.sleepStageDao().saveVerifiedSleep(parsed.session, parsed.stages)
                    count++
                }.onFailure { err ->
                    Log.w(TAG, "Skip malformed sleep record ${record.metadata.id}", err)
                }
            }
        }
        return count
    }

    private suspend fun importHeartRate(
        manager: HealthConnectManager,
        db: Rw3Database,
        preferredStart: Instant,
        fallbackStart: Instant,
        end: Instant
    ): Int {
        var count = 0
        val hrDao = db.heartRateDao()
        readAllPagesWithFallback(manager, HeartRateRecord::class.java, preferredStart, fallbackStart, end) { page ->
            val batch = ArrayList<HeartRateEntity>(500)
            for (record in page) {
                for (sample in record.samples) {
                    val bpm = sample.beatsPerMinute.toInt()
                    val ts = sample.time.toEpochMilli()
                    if (bpm in 30..240 && ts > 0L) {
                        batch.add(HeartRateEntity(id = -ts, timestamp = ts, bpm = bpm))
                        count++
                        if (batch.size >= 500) {
                            hrDao.insertAll(batch.toList())
                            batch.clear()
                        }
                    }
                }
            }
            if (batch.isNotEmpty()) {
                hrDao.insertAll(batch)
            }
        }
        return count
    }

    private suspend fun importDailyActivity(
        manager: HealthConnectManager,
        db: Rw3Database,
        hasHistoryPerm: Boolean,
        canSteps: Boolean,
        canCalories: Boolean
    ): Int {
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)
        val earliestDate = if (hasHistoryPerm) today.minusYears(3) else today.minusDays(29)
        val nowMs = System.currentTimeMillis()
        val latestTodayStepsEnd = if (canSteps) findLatestRecordEndMillis(manager, StepsRecord::class.java, today, zone) else null
        val latestTodayCalEnd = if (canCalories) findLatestRecordEndMillis(manager, ActiveCaloriesBurnedRecord::class.java, today, zone) else null
        val latestTodayMs = listOfNotNull(latestTodayStepsEnd, latestTodayCalEnd).maxOrNull() ?: nowMs

        val stepsByDay = HashMap<LocalDate, Int>()
        val kcalByDay = HashMap<LocalDate, Int>()
        var loggedRawCal = false

        var chunkEnd = today.plusDays(1)
        var emptyChunks = 0
        while (chunkEnd.isAfter(earliestDate)) {
            val chunkStart = maxOfDate(earliestDate, chunkEnd.minusDays(90))
            val range = LocalTimeRangeFilter.Builder()
                .setStartTime(chunkStart.atStartOfDay())
                .setEndTime(chunkEnd.atStartOfDay())
                .build()

            var chunkHasData = false
            if (canSteps) {
                val buckets = runCatching {
                    aggregateDailyPeriod(manager, range, StepsRecord.STEPS_COUNT_TOTAL)
                }.getOrDefault(emptyList())
                for (bucket in buckets) {
                    val count = bucket.get(StepsRecord.STEPS_COUNT_TOTAL)?.toInt()?.takeIf { it > 0 } ?: continue
                    val day = bucket.startTime.toLocalDate()
                    stepsByDay[day] = (stepsByDay[day] ?: 0).coerceAtLeast(count)
                    chunkHasData = true
                }
            }
            if (canCalories) {
                val buckets = runCatching {
                    aggregateDailyPeriod(manager, range, ActiveCaloriesBurnedRecord.ACTIVE_CALORIES_TOTAL)
                }.getOrDefault(emptyList())
                for (bucket in buckets) {
                    val energy = bucket.get(ActiveCaloriesBurnedRecord.ACTIVE_CALORIES_TOTAL) ?: continue
                    val raw = energy.inCalories
                    if (!loggedRawCal && raw > 0.0) {
                        Log.i(TAG, "Raw daily energy.inCalories sample = $raw (${bucket.startTime})")
                        loggedRawCal = true
                    }
                    val kcal = MiFitnessHealthMapper.energyCaloriesToKcal(raw) ?: continue
                    val day = bucket.startTime.toLocalDate()
                    kcalByDay[day] = (kcalByDay[day] ?: 0).coerceAtLeast(kcal)
                    chunkHasData = true
                }
            }
            emptyChunks = if (chunkHasData) 0 else emptyChunks + 1
            chunkEnd = chunkStart
            if (emptyChunks >= 4 && !hasHistoryPerm) break
        }

        val allDays = (stepsByDay.keys + kcalByDay.keys).toSortedSet()
        val dao = db.activityRecordDao()
        val toSave = ArrayList<ActivityRecordEntity>(allDays.size)
        for (day in allDays) {
            val dayStartMs = day.atStartOfDay(zone).toInstant().toEpochMilli()
            val dayEndMs = day.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
            val existing = dao.findDaily(dayStartMs, dayEndMs)
            val obsTs = if (day == today) minOf(nowMs, latestTodayMs) else dayEndMs - 1L
            MiFitnessHealthMapper.mergeDailyTotals(
                old = existing,
                dayStartMs = dayStartMs,
                timestampMs = obsTs,
                steps = stepsByDay[day],
                calories = kcalByDay[day]
            )?.let { merged ->
                toSave.add(merged)
            }
        }
        if (toSave.isNotEmpty()) {
            dao.insert(toSave)
        }
        return toSave.size
    }

    private suspend fun importSpo2(
        manager: HealthConnectManager,
        db: Rw3Database,
        preferredStart: Instant,
        fallbackStart: Instant,
        end: Instant
    ): Int {
        var count = 0
        val dao = db.activityRecordDao()
        readAllPagesWithFallback(manager, OxygenSaturationRecord::class.java, preferredStart, fallbackStart, end) { page ->
            val records = page.mapNotNull { rec ->
                val pct = rec.percentage.value.roundToInt()
                if (pct !in 70..100) return@mapNotNull null
                val ts = rec.time.toEpochMilli()
                val docId = rec.metadata.id.takeIf { it.isNotBlank() } ?: ts.toString()
                ActivityRecordEntity(
                    id = "hc-spo2:$docId",
                    category = "spo2",
                    timestamp = ts,
                    spo2 = pct,
                    sourceFileId = "${MiFitnessHealthMapper.HC_SOURCE_PREFIX}spo2:$docId",
                    formatVersion = MiFitnessHealthMapper.HC_FORMAT_VERSION
                )
            }
            if (records.isNotEmpty()) {
                dao.insert(records)
                count += records.size
            }
        }
        return count
    }

    private suspend fun importWorkouts(
        manager: HealthConnectManager,
        db: Rw3Database,
        preferredStart: Instant,
        fallbackStart: Instant,
        end: Instant
    ): WorkoutImportStats {
        val canSteps = hasPermission(context, READ_STEPS)
        val canCalories = hasPermission(context, READ_CALORIES)
        val canDistance = hasPermission(context, READ_DISTANCE)
        val canSpeed = hasPermission(context, READ_SPEED)
        val canElevation = hasPermission(context, READ_ELEVATION)
        val canHr = hasPermission(context, READ_HEART_RATE)
        val canRoutes = hasPermission(context, READ_EXERCISE_ROUTES)

        val dao = db.activityRecordDao()
        var count = 0
        var routesImported = 0
        var routesPending = 0
        var loggedWorkoutCal = false

        readAllPagesWithFallback(manager, ExerciseSessionRecord::class.java, preferredStart, fallbackStart, end) { page ->
            val batch = ArrayList<ActivityRecordEntity>(page.size)
            for (session in page) {
                runCatching {
                    val startInst = session.startTime
                    val endInst = session.endTime
                    val startMs = startInst.toEpochMilli()
                    val endMs = endInst.toEpochMilli()
                    val durationSec = ((endMs - startMs) / 1000L).toInt().takeIf { it > 0 }
                    val range = TimeInstantRangeFilter.Builder()
                        .setStartTime(startInst)
                        .setEndTime(endInst)
                        .build()

                    val distMeters = if (canDistance) {
                        aggregateSingle(manager, range, DistanceRecord.DISTANCE_TOTAL)?.inMeters?.takeIf { it > 0.0 }
                    } else null

                    val elevMeters = if (canElevation) {
                        aggregateSingle(manager, range, ElevationGainedRecord.ELEVATION_GAINED_TOTAL)?.inMeters?.takeIf { it > 0.0 }
                    } else null

                    val steps = if (canSteps) {
                        aggregateSingle(manager, range, StepsRecord.STEPS_COUNT_TOTAL)?.toInt()?.takeIf { it > 0 }
                    } else null

                    val calories = if (canCalories) {
                        val energy = aggregateSingle(manager, range, ActiveCaloriesBurnedRecord.ACTIVE_CALORIES_TOTAL)
                        if (energy != null && !loggedWorkoutCal && energy.inCalories > 0.0) {
                            Log.i(TAG, "Raw workout energy.inCalories=${energy.inCalories} startMs=$startMs dist=$distMeters steps=$steps")
                            loggedWorkoutCal = true
                        }
                        MiFitnessHealthMapper.energyCaloriesToKcal(energy?.inCalories)
                    } else null

                    val avgHr = if (canHr) aggregateSingle(manager, range, HeartRateRecord.BPM_AVG)?.toInt()?.takeIf { it in 30..240 } else null
                    val minHr = if (canHr) aggregateSingle(manager, range, HeartRateRecord.BPM_MIN)?.toInt()?.takeIf { it in 30..240 } else null
                    val maxHr = if (canHr) aggregateSingle(manager, range, HeartRateRecord.BPM_MAX)?.toInt()?.takeIf { it in 30..240 } else null

                    val speedAvgMps = if (canSpeed) {
                        aggregateSingle(manager, range, SpeedRecord.SPEED_AVG)?.inMetersPerSecond?.takeIf { it > 0.05 }
                    } else null
                    val avgSpeed = MiFitnessHealthMapper.computeSpeedMps(
                        distanceMeters = distMeters,
                        durationSec = durationSec,
                        speedSamplesMps = listOfNotNull(speedAvgMps)
                    )

                    val hasSessionRoute = session.hasRoute()
                    val routeJson = if (canRoutes && hasSessionRoute) {
                        val pts = session.route?.routeLocations?.map { loc ->
                            RoutePoint(
                                lat = loc.latitude,
                                lon = loc.longitude,
                                altMeters = loc.altitude?.inMeters
                            )
                        }.orEmpty()
                        MiFitnessHealthMapper.encodeRoute(pts)?.also { routesImported++ }
                    } else {
                        if (hasSessionRoute && !canRoutes) routesPending++
                        null
                    }

                    val nearby = dao.findWorkoutsNear(startMs - 180_000L, startMs + 180_000L)
                    val matchedExisting = nearby.firstOrNull { MiFitnessHealthMapper.isSameWorkout(it, startMs, endMs) }
                    val hcId = session.metadata.id.takeIf { it.isNotBlank() } ?: startMs.toString()
                    val recordId = matchedExisting?.id ?: "hc-workout:$hcId"

                    val incoming = ActivityRecordEntity(
                        id = recordId,
                        category = "workout",
                        timestamp = startMs,
                        endTimestamp = endMs,
                        steps = steps,
                        calories = calories,
                        averageHeartRate = avgHr,
                        minHeartRate = minHr,
                        maxHeartRate = maxHr,
                        activeSeconds = durationSec,
                        sportType = MiFitnessHealthMapper.fromHealthConnectExerciseType(session.exerciseType),
                        distanceMeters = distMeters,
                        elevationGainMeters = elevMeters,
                        avgSpeedMps = avgSpeed,
                        routeJson = routeJson,
                        sourceFileId = "${MiFitnessHealthMapper.HC_SOURCE_PREFIX}workout:$hcId",
                        formatVersion = MiFitnessHealthMapper.HC_FORMAT_VERSION
                    )
                    batch.add(matchedExisting?.let { MiFitnessHealthMapper.mergeRecord(it, incoming) } ?: incoming)
                    count++
                }.onFailure { err ->
                    Log.w(TAG, "Skip workout session ${session.metadata.id}", err)
                }
            }
            if (batch.isNotEmpty()) {
                dao.insert(batch)
            }
        }
        Log.i(TAG, "Workouts imported=$count routesImported=$routesImported routesPendingPermission=$routesPending")
        return WorkoutImportStats(count, routesImported, routesPending)
    }

    private suspend fun <T : Record> readAllPagesWithFallback(
        manager: HealthConnectManager,
        recordClass: Class<T>,
        preferredStart: Instant,
        fallbackStart: Instant,
        end: Instant,
        onPage: suspend (List<T>) -> Unit
    ) {
        try {
            readAllPages(manager, recordClass, preferredStart, end, onPage)
        } catch (err: Throwable) {
            if (preferredStart.isBefore(fallbackStart) && err is HealthConnectException) {
                Log.w(TAG, "Retrying ${recordClass.simpleName} with 30-day window after full-history error", err)
                readAllPages(manager, recordClass, fallbackStart, end, onPage)
            } else {
                throw err
            }
        }
    }

    private suspend fun <T : Record> readAllPages(
        manager: HealthConnectManager,
        recordClass: Class<T>,
        start: Instant,
        end: Instant,
        onPage: suspend (List<T>) -> Unit
    ) {
        val filter = TimeInstantRangeFilter.Builder().setStartTime(start).setEndTime(end).build()
        var pageToken = -1L
        var pages = 0
        do {
            val builder = ReadRecordsRequestUsingFilters.Builder(recordClass)
                .setTimeRangeFilter(filter)
                .addDataOrigins(miOrigin)
                .setPageSize(1000)
            if (pageToken > 0L) builder.setPageToken(pageToken)

            val response = suspendCancellableCoroutine<ReadRecordsResponse<T>> { cont ->
                manager.readRecords(
                    builder.build(),
                    callbackExecutor,
                    object : OutcomeReceiver<ReadRecordsResponse<T>, HealthConnectException> {
                        override fun onResult(result: ReadRecordsResponse<T>) = cont.resume(result)
                        override fun onError(error: HealthConnectException) = cont.resumeWithException(error)
                    }
                )
            }
            if (response.records.isNotEmpty()) {
                onPage(response.records)
            }
            pageToken = response.nextPageToken
            pages++
        } while (pageToken > 0L && pages < 500)
    }

    private suspend fun <T : Record> findLatestRecordEndMillis(
        manager: HealthConnectManager,
        recordClass: Class<T>,
        day: LocalDate,
        zone: ZoneId
    ): Long? {
        val filter = TimeInstantRangeFilter.Builder()
            .setStartTime(day.atStartOfDay(zone).toInstant())
            .setEndTime(Instant.now())
            .build()
        val req = ReadRecordsRequestUsingFilters.Builder(recordClass)
            .setTimeRangeFilter(filter)
            .addDataOrigins(miOrigin)
            .setAscending(false)
            .setPageSize(1)
            .build()
        val res = runCatching {
            suspendCancellableCoroutine<ReadRecordsResponse<T>> { cont ->
                manager.readRecords(
                    req,
                    callbackExecutor,
                    object : OutcomeReceiver<ReadRecordsResponse<T>, HealthConnectException> {
                        override fun onResult(result: ReadRecordsResponse<T>) = cont.resume(result)
                        override fun onError(error: HealthConnectException) = cont.resumeWithException(error)
                    }
                )
            }
        }.getOrNull() ?: return null
        val rec = res.records.firstOrNull() ?: return null
        return when (rec) {
            is StepsRecord -> rec.endTime.toEpochMilli()
            is ActiveCaloriesBurnedRecord -> rec.endTime.toEpochMilli()
            else -> null
        }
    }

    private suspend fun <T> aggregateDailyPeriod(
        manager: HealthConnectManager,
        range: LocalTimeRangeFilter,
        aggregationType: AggregationType<T>
    ): List<AggregateRecordsGroupedByPeriodResponse<T>> {
        val req = AggregateRecordsRequest.Builder<T>(range)
            .addAggregationType(aggregationType)
            .addDataOriginsFilter(miOrigin)
            .build()
        return suspendCancellableCoroutine { cont ->
            manager.aggregateGroupByPeriod(
                req,
                Period.ofDays(1),
                callbackExecutor,
                object : OutcomeReceiver<List<AggregateRecordsGroupedByPeriodResponse<T>>, HealthConnectException> {
                    override fun onResult(result: List<AggregateRecordsGroupedByPeriodResponse<T>>) = cont.resume(result)
                    override fun onError(error: HealthConnectException) = cont.resumeWithException(error)
                }
            )
        }
    }

    private suspend fun <T> aggregateSingle(
        manager: HealthConnectManager,
        range: TimeRangeFilter,
        aggregationType: AggregationType<T>
    ): T? {
        val req = AggregateRecordsRequest.Builder<T>(range)
            .addAggregationType(aggregationType)
            .addDataOriginsFilter(miOrigin)
            .build()
        val response = runCatching {
            suspendCancellableCoroutine<AggregateRecordsResponse<T>> { cont ->
                manager.aggregate(
                    req,
                    callbackExecutor,
                    object : OutcomeReceiver<AggregateRecordsResponse<T>, HealthConnectException> {
                        override fun onResult(result: AggregateRecordsResponse<T>) = cont.resume(result)
                        override fun onError(error: HealthConnectException) = cont.resumeWithException(error)
                    }
                )
            }
        }.getOrNull() ?: return null
        return response.get(aggregationType)
    }

    private fun maxOfDate(a: LocalDate, b: LocalDate): LocalDate = if (a.isAfter(b)) a else b
}
