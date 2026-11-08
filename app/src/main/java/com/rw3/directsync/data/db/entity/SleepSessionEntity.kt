package com.rw3.directsync.data.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.ColumnInfo

/** Totals reported by the watch for one completed sleep session. */
@Entity(tableName = "sleep_sessions")
data class SleepSessionEntity(
    @PrimaryKey val startTimestamp: Long,
    val endTimestamp: Long,
    val totalMinutes: Int,
    val deepMinutes: Int,
    val lightMinutes: Int,
    val remMinutes: Int,
    val awakeMinutes: Int,
    val sleepScore: Int?,
    val averageHeartRate: Int?,
    val sourceFileId: String,
    @ColumnInfo(defaultValue = "1") val hasStageData: Boolean = true
)
