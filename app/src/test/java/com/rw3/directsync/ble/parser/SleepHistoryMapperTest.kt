package com.rw3.directsync.ble.parser

import com.rw3.directsync.data.SleepHistoryMapper
import org.junit.Assert.*
import org.junit.Test

class SleepHistoryMapperTest {
    private val start = 1700000000000L
    private fun minute(n: Int) = start + n * 60_000L
    @Test fun intervalWithoutStagesKeepsDurationAndMarksPhasesUnknown() {
        val parsed = SleepHistoryMapper.map(start, minute(480), "healthconnect:test", emptyList())
        assertEquals(480, parsed.session.totalMinutes)
        assertFalse(parsed.session.hasStageData); assertTrue(parsed.stages.isEmpty())
        assertNull(parsed.session.sleepScore)
    }
    @Test fun awakeTimeIsExcludedAndStagesKeepOriginalTimestamps() {
        val parsed = SleepHistoryMapper.map(start, minute(480), "healthconnect:test", listOf(
            SleepHistoryMapper.Stage(start, minute(100), 5),
            SleepHistoryMapper.Stage(minute(100), minute(120), 1),
            SleepHistoryMapper.Stage(minute(120), minute(400), 4),
            SleepHistoryMapper.Stage(minute(400), minute(480), 6)))
        assertEquals(460, parsed.session.totalMinutes)
        assertEquals(100, parsed.session.deepMinutes); assertEquals(280, parsed.session.lightMinutes)
        assertEquals(80, parsed.session.remMinutes); assertEquals(20, parsed.session.awakeMinutes)
        assertTrue(parsed.session.hasStageData)
        assertEquals(listOf("DEEP", "AWAKE", "LIGHT", "REM"), parsed.stages.map { it.stage })
        assertEquals(start, parsed.stages.first().startTimestamp)
    }
    @Test fun malformedAndOverlappingSessionsAreRejected() {
        assertThrows(IllegalArgumentException::class.java) { SleepHistoryMapper.map(start, start, "test", emptyList()) }
        assertThrows(IllegalArgumentException::class.java) { SleepHistoryMapper.map(start, minute(60), "test", listOf(
            SleepHistoryMapper.Stage(start, minute(40), 4), SleepHistoryMapper.Stage(minute(30), minute(60), 5))) }
    }
}
