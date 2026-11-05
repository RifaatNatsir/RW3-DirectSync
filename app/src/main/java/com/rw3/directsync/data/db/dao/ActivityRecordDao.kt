package com.rw3.directsync.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.rw3.directsync.data.db.entity.ActivityRecordEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ActivityRecordDao {
    // Repeated downloads must not reset the export flag or duplicate measurements.
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(records: List<ActivityRecordEntity>)

    @Query("SELECT * FROM activity_records WHERE category = 'daily' ORDER BY timestamp DESC LIMIT 1")
    fun observeDaily(): Flow<ActivityRecordEntity?>

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
