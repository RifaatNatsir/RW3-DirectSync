package com.rw3.directsync.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import com.rw3.directsync.data.db.entity.ActivityRecordEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ActivityRecordDao {
    // Repeated downloads must not reset the export flag or duplicate measurements.
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertNew(records: List<ActivityRecordEntity>)

    @Update
    suspend fun update(record: ActivityRecordEntity)

    @Query("SELECT * FROM activity_records WHERE id = :id LIMIT 1")
    suspend fun find(id: String): ActivityRecordEntity?

    @Query("SELECT * FROM activity_records WHERE category = 'daily' AND timestamp >= :start AND timestamp < :end ORDER BY timestamp DESC LIMIT 1")
    suspend fun findDaily(start: Long, end: Long): ActivityRecordEntity?

    @Transaction
    suspend fun insert(records: List<ActivityRecordEntity>) {
        records.forEach { incoming ->
            val old = find(incoming.id)
            if (old == null) insertNew(listOf(incoming))
            else if (old.copy(syncedToWebhook = false) != incoming.copy(syncedToWebhook = false)) {
                update(incoming.copy(syncedToWebhook = false))
            }
        }
    }

    @Transaction
    suspend fun saveDailyTotals(timestamp: Long, steps: Int?, calories: Int?, start: Long, end: Long) {
        if (steps == null && calories == null) return
        val id = "daily-live:$start"
        val old = find(id)
        val base = old ?: findDaily(start, end)
        val record = (base ?: ActivityRecordEntity(id = id, category = "daily", timestamp = timestamp,
            sourceFileId = "realtime", formatVersion = 0)).copy(
            id = id, timestamp = timestamp, steps = steps ?: base?.steps,
            calories = calories ?: base?.calories, sourceFileId = "realtime", formatVersion = 0,
            syncedToWebhook = false)
        // Identical totals retain both their observation time and webhook acknowledgement.
        if (old == null) insertNew(listOf(record))
        else if (old.copy(timestamp = timestamp, syncedToWebhook = false) != record) update(record)
    }

    @Query("SELECT * FROM activity_records WHERE category = 'daily' ORDER BY timestamp DESC LIMIT 1")
    fun observeDaily(): Flow<ActivityRecordEntity?>

    @Query("SELECT * FROM activity_records WHERE category = 'daily' AND timestamp >= (SELECT MAX(timestamp) - 172800000 FROM activity_records WHERE category = 'daily') ORDER BY timestamp DESC")
    fun observeDailySnapshots(): Flow<List<ActivityRecordEntity>>

    @Query("SELECT * FROM activity_records WHERE category = 'workout' ORDER BY timestamp DESC LIMIT 20")
    fun observeWorkouts(): Flow<List<ActivityRecordEntity>>

    @Query("SELECT * FROM activity_records WHERE category = :category ORDER BY timestamp DESC LIMIT 1")
    fun observeMeasurement(category: String): Flow<ActivityRecordEntity?>

    @Query("SELECT COUNT(*) FROM activity_records")
    fun observeCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM activity_records WHERE syncedToWebhook = 0")
    fun observeUnsyncedCount(): Flow<Int>

    @Query("SELECT * FROM activity_records WHERE syncedToWebhook = 0 ORDER BY timestamp LIMIT :limit")
    suspend fun getUnsyncedRecords(limit: Int): List<ActivityRecordEntity>

    @Query("UPDATE activity_records SET syncedToWebhook = 1 WHERE id IN (:ids)")
    suspend fun markAsSynced(ids: List<String>)
}
