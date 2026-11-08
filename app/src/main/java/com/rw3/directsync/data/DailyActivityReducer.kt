package com.rw3.directsync.data

import com.rw3.directsync.data.db.entity.ActivityRecordEntity
import com.rw3.directsync.data.db.entity.DailyActivityMinuteEntity
import java.time.Instant
import java.time.ZoneId

/** Merge cumulative snapshots with unique minute increments, within one local calendar day. */
object DailyActivityReducer {
    fun merge(snapshots: List<ActivityRecordEntity>, minutes: List<DailyActivityMinuteEntity>,
              zone: ZoneId = ZoneId.systemDefault()): ActivityRecordEntity? {
        val newest = (snapshots.map { it.timestamp } + minutes.map { it.timestamp }).maxOrNull() ?: return null
        fun day(timestamp: Long) = Instant.ofEpochMilli(timestamp).atZone(zone).toLocalDate()
        val date = day(newest)
        val daily = snapshots.filter { day(it.timestamp) == date }.sortedByDescending { it.timestamp }
        val points = minutes.filter { day(it.timestamp) == date }.distinctBy { it.timestamp }
        fun total(values: List<Int?>): Int? = values.filterNotNull().takeIf { it.isNotEmpty() }?.sum()
        val steps = (daily.mapNotNull { it.steps } + listOfNotNull(total(points.map { it.steps }))).maxOrNull()
        val calories = (daily.mapNotNull { it.calories } + listOfNotNull(total(points.map { it.calories }))).maxOrNull()
        val base = daily.firstOrNull() ?: ActivityRecordEntity(id = "daily-merged:$date", category = "daily",
            timestamp = newest, sourceFileId = "minute-records", formatVersion = 0)
        return base.copy(timestamp = newest, steps = steps, calories = calories,
            restingHeartRate = daily.firstNotNullOfOrNull { it.restingHeartRate },
            averageHeartRate = daily.firstNotNullOfOrNull { it.averageHeartRate },
            minHeartRate = daily.mapNotNull { it.minHeartRate }.minOrNull(),
            maxHeartRate = daily.mapNotNull { it.maxHeartRate }.maxOrNull(),
            standingHours = daily.mapNotNull { it.standingHours }.maxOrNull(),
            spo2 = daily.firstNotNullOfOrNull { it.spo2 }, stress = daily.firstNotNullOfOrNull { it.stress })
    }
}
