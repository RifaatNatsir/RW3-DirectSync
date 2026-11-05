package com.rw3.directsync.network

import android.content.Context
import android.util.Log
import com.rw3.directsync.data.db.Rw3Database
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Local Webhook Dispatcher (F5) responsible for exporting unsynced time-series
 * Heart Rate and Sleep metrics to a designated HTTP endpoint via JSON POST payloads.
 */
class WebhookDispatcher(context: Context) {

    companion object {
        private const val TAG = "WebhookDispatcher"
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        private const val BATCH_LIMIT = 200
    }

    private val db = Rw3Database.getDatabase(context)
    private val hrDao = db.heartRateDao()
    private val sleepDao = db.sleepStageDao()

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    sealed class DispatchResult {
        data class Success(val hrExported: Int, val sleepExported: Int, val activityExported: Int = 0) : DispatchResult()
        data object NoPendingData : DispatchResult()
        data class Error(val message: String, val statusCode: Int? = null) : DispatchResult()
    }

    /**
     * Queries pending unsynced records from Room, builds JSON, dispatches HTTP POST,
     * and marks records as synced in SQLite upon receiving HTTP 200/201.
     */
    suspend fun dispatchPendingRecords(
        webhookUrl: String,
        deviceMac: String
    ): DispatchResult = withContext(Dispatchers.IO) {
        val sanitizedUrl = webhookUrl.trim().toHttpUrlOrNull()
        if (sanitizedUrl == null) {
            return@withContext DispatchResult.Error("URL Webhook tidak valid (harus dimulai dengan http:// atau https://)")
        }

        val pendingHr = hrDao.getUnsyncedRecords(limit = BATCH_LIMIT)
        val pendingSleep = sleepDao.getUnsyncedRecords(limit = BATCH_LIMIT)

        val pendingActivity = db.activityRecordDao().getUnsyncedRecords(BATCH_LIMIT)

        if (pendingHr.isEmpty() && pendingSleep.isEmpty() && pendingActivity.isEmpty()) {
            return@withContext DispatchResult.NoPendingData
        }

        val jsonPayload = JSONObject().apply {
            put("schema_version", 2)
            put("activity_record_count", pendingActivity.size)
            put("activity_records", JSONArray().apply {
                pendingActivity.forEach { record ->
                    put(JSONObject().apply {
                        put("id", record.id); put("category", record.category)
                        put("timestamp", record.timestamp); put("end_timestamp", record.endTimestamp ?: JSONObject.NULL)
                        put("steps", record.steps ?: JSONObject.NULL); put("active_calories", record.calories ?: JSONObject.NULL)
                        put("resting_heart_rate", record.restingHeartRate ?: JSONObject.NULL)
                        put("average_heart_rate", record.averageHeartRate ?: JSONObject.NULL)
                        put("min_heart_rate", record.minHeartRate ?: JSONObject.NULL); put("max_heart_rate", record.maxHeartRate ?: JSONObject.NULL)
                        put("spo2", record.spo2 ?: JSONObject.NULL); put("stress", record.stress ?: JSONObject.NULL)
                        put("standing_hours", record.standingHours ?: JSONObject.NULL); put("active_seconds", record.activeSeconds ?: JSONObject.NULL)
                        put("sport_type", record.sportType ?: JSONObject.NULL)
                        put("source", "watch"); put("source_file_id", record.sourceFileId); put("format_version", record.formatVersion)
                    })
                }
            })
            put("device_mac", deviceMac)
            put("export_timestamp", System.currentTimeMillis())
            put("heart_rate_count", pendingHr.size)
            put("sleep_stage_count", pendingSleep.size)

            val hrArray = JSONArray()
            pendingHr.forEach { record ->
                hrArray.put(JSONObject().apply {
                    put("id", record.id)
                    put("timestamp", record.timestamp)
                    put("bpm", record.bpm)
                })
            }
            put("heart_rate_records", hrArray)

            val sleepArray = JSONArray()
            pendingSleep.forEach { record ->
                sleepArray.put(JSONObject().apply {
                    put("id", record.id)
                    put("start_timestamp", record.startTimestamp)
                    put("end_timestamp", record.endTimestamp)
                    put("stage", record.stage)
                    put("duration_minutes", record.durationMinutes)
                    put("raw_type", record.rawType)
                })
            }
            put("sleep_stage_records", sleepArray)
        }

        val requestBody = jsonPayload.toString().toRequestBody(JSON_MEDIA_TYPE)
        val request = Request.Builder()
            .url(sanitizedUrl)
            .addHeader("User-Agent", "RW3-DirectSync-Android/1.0")
            .post(requestBody)
            .build()

        try {
            httpClient.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val hrIds = pendingHr.map { it.id }
                    val sleepIds = pendingSleep.map { it.id }

                    if (pendingActivity.isNotEmpty()) db.activityRecordDao().markAsSynced(pendingActivity.map { it.id })
                    if (hrIds.isNotEmpty()) hrDao.markAsSynced(hrIds)
                    if (sleepIds.isNotEmpty()) sleepDao.markAsSynced(sleepIds)

                    Log.i(
                        TAG,
                        "Webhook POST success (${response.code}). Synced ${hrIds.size} HR & ${sleepIds.size} Sleep records."
                    )
                    DispatchResult.Success(hrExported = hrIds.size, sleepExported = sleepIds.size, activityExported = pendingActivity.size)
                } else {
                    val err = "Webhook server responded with HTTP ${response.code}: ${response.message}"
                    Log.e(TAG, err)
                    DispatchResult.Error(err, response.code)
                }
            }
        } catch (e: IOException) {
            val err = "Network error during Webhook dispatch: ${e.message}"
            Log.e(TAG, err, e)
            DispatchResult.Error(err)
        } catch (e: Exception) {
            val err = "Unexpected error during Webhook dispatch: ${e.message}"
            Log.e(TAG, err, e)
            DispatchResult.Error(err)
        }
    }
}

