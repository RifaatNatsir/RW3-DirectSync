package com.rw3.directsync.data

import com.rw3.directsync.data.db.entity.SleepSessionEntity
import com.rw3.directsync.data.db.entity.SleepStageEntity
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

class SleepInsightsTest {
    private val zone = ZoneId.of("Asia/Makassar")
    private fun at(value: String) = LocalDateTime.parse(value).atZone(zone).toInstant().toEpochMilli()
    private fun session(start: String, end: String, minutes: Int, detailed: Boolean = true) = SleepSessionEntity(
        at(start), at(end), minutes, 0, minutes, 0, 0, null, null, "test-source", detailed)
    private fun stage(start: Long, end: Long, name: String = "LIGHT", source: String = "test-source") =
        SleepStageEntity(startTimestamp = start, endTimestamp = end, stage = name,
            durationMinutes = ((end - start) / 60_000).toInt(), rawType = 4, sourceFileId = source)

    @Test fun `missing dates stay absent and session belongs to wake date`() {
        val s = session("2026-11-08T23:50", "2026-11-09T07:00", 430)
        val days = SleepInsights.days(listOf(s), LocalDate.parse("2026-11-09"), 7, zone)
        assertEquals(7, days.size)
        assertNull(days[5].minutes)
        assertEquals(430, days.last().minutes)
        assertEquals(s, days.last().primary)
    }
    @Test fun `daily totals include naps but main schedule uses longest session`() {
        val night = session("2026-11-09T01:56", "2026-11-09T05:45", 229)
        val nap = session("2026-11-09T12:30", "2026-11-09T13:00", 30, false)
        val day = SleepInsights.days(listOf(nap, night), LocalDate.parse("2026-11-09"), 7, zone).last()
        assertEquals(259, day.minutes)
        assertEquals(night, day.primary)
    }
    @Test fun `thirty day window crosses months and excludes outside sessions`() {
        val old = session("2026-10-10T01:00", "2026-10-10T08:00", 420)
        val boundary = session("2026-10-11T01:00", "2026-10-11T08:00", 420)
        val days = SleepInsights.days(listOf(old, boundary), LocalDate.parse("2026-11-09"), 30, zone)
        assertEquals(LocalDate.parse("2026-10-11"), days.first().date)
        assertEquals(1, days.count { it.minutes != null })
        assertEquals(420, days.first().minutes)
    }
    @Test fun `midnight bedtime median and deviation wrap correctly`() {
        val stats = SleepInsights.clockStats(listOf(at("2026-11-08T23:50"), at("2026-11-09T00:10")), zone)!!
        assertEquals(0.0, stats.minute, .001)
        assertEquals(10.0, stats.deviation, .001)
        assertEquals(2, stats.count)
    }
    @Test fun `odd sample median and singleton have factual values`() {
        val times = listOf("2026-11-07T23:30", "2026-11-08T00:00", "2026-11-09T00:10").map(::at)
        assertEquals(0.0, SleepInsights.clockStats(times, zone)!!.minute, .001)
        assertEquals(40.0 / 3, SleepInsights.clockStats(times, zone)!!.deviation, .001)
        assertEquals(5.0, SleepInsights.clockStats(listOf(at("2026-11-09T00:05")), zone)!!.minute, .001)
        assertNull(SleepInsights.clockStats(emptyList(), zone))
    }
    @Test fun `local zone determines wake date`() {
        val s = session("2026-11-09T00:10", "2026-11-09T06:10", 360)
        val utcDays = SleepInsights.days(listOf(s), LocalDate.parse("2026-11-09"), 2, ZoneId.of("UTC"))
        assertEquals(360, utcDays.first().minutes)
        assertNull(utcDays.last().minutes)
    }
    @Test fun `timeline uses real subminute timestamps and leaves gaps unknown`() {
        val s = session("2026-11-09T01:56", "2026-11-09T05:45", 229)
        val a = s.startTimestamp + 30_000
        val b = a + 90_000
        val spans = SleepInsights.timeline(s, listOf(stage(a, b, "DEEP")))
        assertEquals(listOf("UNKNOWN", "DEEP", "UNKNOWN"), spans.map { it.stage })
        assertEquals(a, spans[1].start)
        assertEquals(90_000L, spans[1].end - spans[1].start)
        assertEquals(s.endTimestamp - s.startTimestamp, spans.sumOf { it.end - it.start })
    }
    @Test fun `timeline clips to session and ignores another source`() {
        val s = session("2026-11-09T01:56", "2026-11-09T05:45", 229)
        val spans = SleepInsights.timeline(s, listOf(stage(s.startTimestamp - 60_000, s.endTimestamp + 60_000),
            stage(s.startTimestamp, s.endTimestamp, "REM", "other")))
        assertEquals(listOf(SleepInsights.Span(s.startTimestamp, s.endTimestamp, "LIGHT")), spans)
    }
    @Test fun `conflicting overlaps are unknown and matching overlaps merge`() {
        val s = session("2026-11-09T01:56", "2026-11-09T05:45", 229)
        val mid = s.startTimestamp + 60_000
        val spans = SleepInsights.timeline(s, listOf(stage(s.startTimestamp, s.endTimestamp), stage(mid, s.endTimestamp, "DEEP")))
        assertEquals(listOf("LIGHT", "UNKNOWN"), spans.map { it.stage })
        assertEquals(1, SleepInsights.timeline(s, listOf(stage(s.startTimestamp, s.endTimestamp), stage(mid, s.endTimestamp))).size)
    }
    @Test fun `sensor only or absent phase rows never invent phases`() {
        val s = session("2026-11-09T01:56", "2026-11-09T05:45", 229, false)
        val rows = listOf(stage(s.startTimestamp, s.endTimestamp, "DEEP"))
        assertEquals(listOf("UNKNOWN"), SleepInsights.timeline(s, rows).map { it.stage })
        assertEquals(listOf("UNKNOWN"), SleepInsights.timeline(s.copy(hasStageData = true), emptyList()).map { it.stage })
    }
    @Test fun `four stages keep chronological order and invalid intervals are ignored`() {
        val s = session("2026-11-09T01:00", "2026-11-09T05:00", 180)
        val names = listOf("DEEP", "LIGHT", "REM", "AWAKE")
        val rows = names.mapIndexed { i, name -> stage(s.startTimestamp + i * 3_600_000, s.startTimestamp + (i + 1) * 3_600_000, name) }
        val spans = SleepInsights.timeline(s, rows.reversed() + stage(s.endTimestamp, s.startTimestamp))
        assertEquals(names, spans.map { it.stage })
    }
}
