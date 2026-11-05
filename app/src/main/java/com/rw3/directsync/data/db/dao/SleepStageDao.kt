package com.rw3.directsync.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.rw3.directsync.data.db.entity.SleepStageEntity
import com.rw3.directsync.data.db.entity.SleepSessionEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface SleepStageDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(record: SleepStageEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(records: List<SleepStageEntity>)

    @Query("SELECT * FROM sleep_stage_records WHERE synced_to_webhook = 0 AND source_file_id IS NOT NULL ORDER BY start_timestamp ASC LIMIT :limit")
    suspend fun getUnsyncedRecords(limit: Int = 100): List<SleepStageEntity>

    @Query("UPDATE sleep_stage_records SET synced_to_webhook = 1 WHERE id IN (:ids)")
    suspend fun markAsSynced(ids: List<Long>)

    @Query("SELECT * FROM sleep_stage_records WHERE start_timestamp >= :startTime AND end_timestamp <= :endTime ORDER BY start_timestamp ASC")
    suspend fun getStagesBetween(startTime: Long, endTime: Long): List<SleepStageEntity>

    @Query("SELECT * FROM sleep_stage_records ORDER BY start_timestamp DESC LIMIT :limit")
    fun observeRecentStages(limit: Int = 30): Flow<List<SleepStageEntity>>

    @Query("SELECT * FROM sleep_stage_records ORDER BY start_timestamp ASC")
    fun observeAllStages(): Flow<List<SleepStageEntity>>

    @Query("SELECT * FROM sleep_stage_records WHERE start_timestamp >= :sinceTimestamp ORDER BY start_timestamp ASC")
    fun observeStagesSince(sinceTimestamp: Long): Flow<List<SleepStageEntity>>

    @Query("SELECT COUNT(*) FROM sleep_stage_records WHERE source_file_id IS NOT NULL")
    fun observeTotalCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM sleep_stage_records WHERE synced_to_webhook = 0 AND source_file_id IS NOT NULL")
    fun observeUnsyncedCount(): Flow<Int>

    @Query("SELECT * FROM sleep_sessions ORDER BY endTimestamp DESC LIMIT 1")
    fun observeLatestSession(): Flow<SleepSessionEntity?>

    @Query("SELECT * FROM sleep_sessions WHERE startTimestamp = :start LIMIT 1")
    suspend fun getSession(start: Long): SleepSessionEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveSession(session: SleepSessionEntity)

    @Query("DELETE FROM sleep_stage_records WHERE source_file_id = :source")
    suspend fun deleteFileStages(source: String)

    @Transaction
    suspend fun saveVerifiedSleep(session: SleepSessionEntity, stages: List<SleepStageEntity>) {
        val existing = getSession(session.startTimestamp)
        if (existing != null && existing.endTimestamp > session.endTimestamp) return
        // Re-fetching an identical file must not create new rows or reset export status.
        if (existing == session) return
        if (existing != null) deleteFileStages(existing.sourceFileId)
        deleteFileStages(session.sourceFileId)
        saveSession(session)
        insertAll(stages)
    }
}

