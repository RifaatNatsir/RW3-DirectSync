package com.rw3.directsync.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Room entity representing categorized sleep stages extracted and parsed
 * from Redmi Watch 3 activity log packets (Huami/Xiaomi Sync protocol).
 */
@Entity(
    tableName = "sleep_stage_records",
    indices = [
        Index(value = ["start_timestamp"]),
        Index(value = ["end_timestamp"]),
        Index(value = ["synced_to_webhook"])
    ]
)
data class SleepStageEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    @ColumnInfo(name = "start_timestamp")
    val startTimestamp: Long, // Epoch timestamp in milliseconds

    @ColumnInfo(name = "end_timestamp")
    val endTimestamp: Long, // Epoch timestamp in milliseconds

    @ColumnInfo(name = "stage")
    val stage: String, // "DEEP", "LIGHT", "REM", "AWAKE"

    @ColumnInfo(name = "duration_minutes")
    val durationMinutes: Int, // Calculated duration in minutes

    @ColumnInfo(name = "raw_type")
    val rawType: Int, // Raw Huami type code (e.g. 112, 113, 114, 115) for IoT research

    @ColumnInfo(name = "synced_to_webhook", defaultValue = "0")
    val syncedToWebhook: Boolean = false,

    @ColumnInfo(name = "source_file_id")
    val sourceFileId: String? = null
)

