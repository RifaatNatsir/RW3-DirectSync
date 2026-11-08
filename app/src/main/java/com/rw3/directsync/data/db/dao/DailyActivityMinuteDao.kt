package com.rw3.directsync.data.db.dao

import androidx.room.*
import com.rw3.directsync.data.db.entity.DailyActivityMinuteEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface DailyActivityMinuteDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(records: List<DailyActivityMinuteEntity>)

    @Query("SELECT * FROM daily_activity_minutes WHERE timestamp BETWEEN :start AND :end")
    suspend fun between(start: Long, end: Long): List<DailyActivityMinuteEntity>

    @Transaction
    suspend fun merge(records: List<DailyActivityMinuteEntity>) {
        if (records.isEmpty()) return
        val old = between(records.minOf { it.timestamp }, records.maxOf { it.timestamp }).associateBy { it.timestamp }
        insertAll(records.map { incoming ->
            val previous = old[incoming.timestamp]
            incoming.copy(steps = incoming.steps ?: previous?.steps, calories = incoming.calories ?: previous?.calories)
        })
    }

    @Query("SELECT * FROM daily_activity_minutes WHERE timestamp >= (SELECT MAX(timestamp) - 172800000 FROM daily_activity_minutes) ORDER BY timestamp DESC")
    fun observeRecent(): Flow<List<DailyActivityMinuteEntity>>
}
