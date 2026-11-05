package com.rw3.directsync.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.rw3.directsync.data.db.entity.HeartRateEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface HeartRateDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(record: HeartRateEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(records: List<HeartRateEntity>)

    @Query("SELECT * FROM heart_rate_records WHERE synced_to_webhook = 0 ORDER BY timestamp ASC LIMIT :limit")
    suspend fun getUnsyncedRecords(limit: Int = 100): List<HeartRateEntity>

    @Query("UPDATE heart_rate_records SET synced_to_webhook = 1 WHERE id IN (:ids)")
    suspend fun markAsSynced(ids: List<Long>)

    @Query("SELECT * FROM heart_rate_records ORDER BY timestamp DESC LIMIT :limit")
    fun observeRecentRecords(limit: Int = 30): Flow<List<HeartRateEntity>>

    @Query("SELECT * FROM heart_rate_records ORDER BY timestamp DESC LIMIT :limit")
    fun observeLatestRecords(limit: Int = 1500): Flow<List<HeartRateEntity>>

    @Query("SELECT * FROM heart_rate_records WHERE timestamp >= :sinceTimestamp ORDER BY timestamp ASC")
    fun observeRecordsSince(sinceTimestamp: Long): Flow<List<HeartRateEntity>>

    @Query("SELECT * FROM heart_rate_records ORDER BY timestamp DESC LIMIT 1")
    suspend fun getLatestRecord(): HeartRateEntity?

    @Query("SELECT COUNT(*) FROM heart_rate_records")
    fun observeTotalCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM heart_rate_records WHERE synced_to_webhook = 0")
    fun observeUnsyncedCount(): Flow<Int>

    @Query("DELETE FROM heart_rate_records WHERE timestamp < :cutoffTimestamp")
    suspend fun deleteOlderThan(cutoffTimestamp: Long): Int
}

