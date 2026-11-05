package com.rw3.directsync.data.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/** Nullable metrics mean the watch did not report a valid value. Timestamps are milliseconds. */
@Entity(tableName = "activity_records")
data class ActivityRecordEntity(
    @PrimaryKey val id: String,
    val category: String,
    val timestamp: Long,
    val endTimestamp: Long? = null,
    val steps: Int? = null,
    val calories: Int? = null,
    val restingHeartRate: Int? = null,
    val averageHeartRate: Int? = null,
    val minHeartRate: Int? = null,
    val maxHeartRate: Int? = null,
    val spo2: Int? = null,
    val stress: Int? = null,
    val standingHours: Int? = null,
    val activeSeconds: Int? = null,
    val sportType: Int? = null,
    val sourceFileId: String,
    val formatVersion: Int,
    val syncedToWebhook: Boolean = false
)
