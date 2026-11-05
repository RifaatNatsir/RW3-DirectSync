package com.rw3.directsync.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Room entity representing raw real-time Heart Rate (BPM) measurements
 * parsed from BLE Characteristic 0x2A37 (Service 0x180D).
 */
@Entity(
    tableName = "heart_rate_records",
    indices = [
        Index(value = ["timestamp"]),
        Index(value = ["synced_to_webhook"])
    ]
)
data class HeartRateEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    @ColumnInfo(name = "timestamp")
    val timestamp: Long, // Epoch timestamp in milliseconds

    @ColumnInfo(name = "bpm")
    val bpm: Int, // Beats Per Minute (typically 30..220)

    @ColumnInfo(name = "synced_to_webhook", defaultValue = "0")
    val syncedToWebhook: Boolean = false
)

