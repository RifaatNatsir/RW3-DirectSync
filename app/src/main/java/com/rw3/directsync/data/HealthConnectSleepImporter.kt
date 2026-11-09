package com.rw3.directsync.data

import android.annotation.SuppressLint
import android.content.Context
import android.health.connect.HealthConnectException
import android.health.connect.HealthConnectManager
import android.health.connect.ReadRecordsRequestUsingFilters
import android.health.connect.ReadRecordsResponse
import android.health.connect.TimeInstantRangeFilter
import android.health.connect.datatypes.DataOrigin
import android.health.connect.datatypes.SleepSessionRecord
import android.os.OutcomeReceiver
import androidx.annotation.RequiresApi
import com.rw3.directsync.data.db.Rw3Database
import kotlinx.coroutines.suspendCancellableCoroutine
import java.time.Instant
import java.time.temporal.ChronoUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Reads only Mi Fitness sleep records; never writes or deletes Health Connect data. */
@RequiresApi(34)
class HealthConnectSleepImporter(private val context: Context) {
    companion object { const val READ_SLEEP = "android.permission.health.READ_SLEEP" }

    @SuppressLint("MissingPermission")
    suspend fun importHistory(): Int {
        val manager = requireNotNull(context.getSystemService(HealthConnectManager::class.java))
        val now = Instant.now()
        val range = TimeInstantRangeFilter.Builder().setStartTime(now.minus(30, ChronoUnit.DAYS)).setEndTime(now).build()
        val origin = DataOrigin.Builder().setPackageName("com.xiaomi.wearable").build()
        var token = -1L
        var count = 0
        do {
            val builder = ReadRecordsRequestUsingFilters.Builder(SleepSessionRecord::class.java)
                .addDataOrigins(origin).setTimeRangeFilter(range).setPageSize(100)
            if (token == -1L) builder.setAscending(false) else builder.setPageToken(token)
            val response = suspendCancellableCoroutine<ReadRecordsResponse<SleepSessionRecord>> { continuation ->
                manager.readRecords(builder.build(), context.mainExecutor,
                    object : OutcomeReceiver<ReadRecordsResponse<SleepSessionRecord>, HealthConnectException> {
                        override fun onResult(result: ReadRecordsResponse<SleepSessionRecord>) {
                            if (continuation.isActive) continuation.resume(result)
                        }
                        override fun onError(error: HealthConnectException) {
                            if (continuation.isActive) continuation.resumeWithException(error)
                        }
                    })
            }
            for (record in response.records) {
                val source = "healthconnect:com.xiaomi.wearable:${record.metadata.id}"
                val parsed = SleepHistoryMapper.map(record.startTime.toEpochMilli(), record.endTime.toEpochMilli(),
                    source, record.stages.map { SleepHistoryMapper.Stage(it.startTime.toEpochMilli(), it.endTime.toEpochMilli(), it.type) })
                Rw3Database.getDatabase(context).sleepStageDao().saveVerifiedSleep(parsed.session, parsed.stages)
                count++
            }
            token = response.nextPageToken
        } while (token != -1L)
        com.rw3.directsync.sync.SyncStatusRepository.get(context).importedSleep(count > 0)
        return count
    }
}
