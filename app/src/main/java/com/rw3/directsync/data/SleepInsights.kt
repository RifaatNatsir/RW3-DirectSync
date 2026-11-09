package com.rw3.directsync.data

import com.rw3.directsync.data.db.entity.SleepSessionEntity
import com.rw3.directsync.data.db.entity.SleepStageEntity
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.abs

/** Read-only summaries. Missing days and unclassified intervals are never converted to zero sleep. */
object SleepInsights {
    data class Day(val date: LocalDate, val sessions: List<SleepSessionEntity>) {
        val minutes: Int? get() = sessions.takeIf { it.isNotEmpty() }?.sumOf { it.totalMinutes }
        val primary: SleepSessionEntity? get() = sessions.maxByOrNull { it.totalMinutes }
    }
    data class ClockStats(val minute: Double, val deviation: Double, val count: Int)
    data class Span(val start: Long, val end: Long, val stage: String)
    const val UNKNOWN = "UNKNOWN"
    val stages = listOf("AWAKE", "REM", "LIGHT", "DEEP", UNKNOWN)

    fun date(timestamp: Long, zone: ZoneId): LocalDate = Instant.ofEpochMilli(timestamp).atZone(zone).toLocalDate()

    fun days(sessions: List<SleepSessionEntity>, end: LocalDate, count: Int, zone: ZoneId): List<Day> {
        require(count in 1..366)
        val grouped = sessions.filter { it.endTimestamp > it.startTimestamp && it.totalMinutes > 0 }
            .groupBy { date(it.endTimestamp, zone) }
        return (count - 1 downTo 0).map { offset ->
            val day = end.minusDays(offset.toLong())
            Day(day, grouped[day].orEmpty().sortedBy { it.startTimestamp })
        }
    }

    /** Unwrap clock times at their largest empty arc, so 23:50 and 00:10 center on midnight. */
    fun clockStats(timestamps: List<Long>, zone: ZoneId): ClockStats? {
        if (timestamps.isEmpty()) return null
        val values = timestamps.map {
            val t = Instant.ofEpochMilli(it).atZone(zone).toLocalTime()
            t.toSecondOfDay() / 60.0
        }.sorted()
        val cut = values.indices.maxBy { index ->
            (if (index == values.lastIndex) values.first() + 1440 else values[index + 1]) - values[index]
        }
        val base = values[(cut + 1) % values.size]
        val unwrapped = values.map { if (it < base) it + 1440 else it }.sorted()
        val n = values.size
        val median = ((unwrapped[(n - 1) / 2] + unwrapped[n / 2]) / 2) % 1440
        val deviation = values.map { val d = abs(it - median); minOf(d, 1440 - d) }.average()
        return ClockStats(median, deviation, n)
    }

    /** Exact timestamps, clipped to this session/source. Conflicting phases remain unknown. */
    fun timeline(session: SleepSessionEntity, records: List<SleepStageEntity>): List<Span> {
        if (session.endTimestamp <= session.startTimestamp) return emptyList()
        val intervals = if (!session.hasStageData) emptyList() else records
            .filter { it.sourceFileId == session.sourceFileId }
            .map { Span(maxOf(session.startTimestamp, it.startTimestamp), minOf(session.endTimestamp, it.endTimestamp), it.stage) }
            .filter { it.end > it.start }
        val boundaries = (listOf(session.startTimestamp, session.endTimestamp) + intervals.flatMap { listOf(it.start, it.end) }).distinct().sorted()
        val result = mutableListOf<Span>()
        boundaries.zipWithNext().forEach { (start, end) ->
            val active = intervals.filter { it.start < end && it.end > start }.map { it.stage }.distinct()
            val stage = active.singleOrNull()?.takeIf { it in stages } ?: UNKNOWN
            if (result.lastOrNull()?.stage == stage) result[result.lastIndex] = result.last().copy(end = end)
            else result += Span(start, end, stage)
        }
        return result
    }
}
