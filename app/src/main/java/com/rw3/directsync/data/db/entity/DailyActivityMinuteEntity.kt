package com.rw3.directsync.data.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/** Increment for one watch minute, shared by overlapping downloaded files. */
@Entity(tableName = "daily_activity_minutes")
data class DailyActivityMinuteEntity(
    @PrimaryKey val timestamp: Long,
    val steps: Int?,
    val calories: Int?,
    val sourceFileId: String
)
