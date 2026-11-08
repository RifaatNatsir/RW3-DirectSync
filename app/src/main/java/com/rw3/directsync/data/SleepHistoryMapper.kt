package com.rw3.directsync.data

import com.rw3.directsync.ble.parser.XiaomiSleepFileParser
import com.rw3.directsync.data.db.entity.SleepSessionEntity
import com.rw3.directsync.data.db.entity.SleepStageEntity

/** Platform-independent conversion of a vendor Health Connect sleep session. */
object SleepHistoryMapper {
    data class Stage(val start: Long, val end: Long, val type: Int)

    fun map(start: Long, end: Long, source: String, values: List<Stage>): XiaomiSleepFileParser.Result {
        require(start > 0 && end > start && end - start <= 48 * 3_600_000L) { "Waktu tidur tidak valid" }
        val sorted = values.sortedBy { it.start }
        require(sorted.all { it.start >= start && it.end <= end && it.end > it.start }) { "Waktu fase tidur tidak valid" }
        require(sorted.zipWithNext().all { (a, b) -> a.end <= b.start }) { "Fase tidur bertumpuk" }
        val durations = sorted.groupBy { it.type }.mapValues { (_, stages) -> stages.sumOf { it.end - it.start } }
        val awakeMillis = listOf(1, 3, 7).sumOf { durations[it] ?: 0L }
        val total = ((end - start - awakeMillis) / 60_000).toInt()
        require(total > 0) { "Sesi tidak berisi durasi tidur" }
        val detailed = sorted.any { it.type in 4..6 }
        val stages = sorted.mapNotNull {
            val name = when (it.type) { 1, 3, 7 -> "AWAKE"; 4 -> "LIGHT"; 5 -> "DEEP"; 6 -> "REM"; else -> null }
            name?.let { name -> SleepStageEntity(startTimestamp = it.start, endTimestamp = it.end,
                stage = name, durationMinutes = ((it.end - it.start) / 60_000).toInt(),
                rawType = it.type, sourceFileId = source) }
        }
        fun minutes(type: Int) = ((durations[type] ?: 0L) / 60_000).toInt()
        return XiaomiSleepFileParser.Result(SleepSessionEntity(start, end, total, minutes(5), minutes(4),
            minutes(6), (awakeMillis / 60_000).toInt(), null, null, source, detailed), stages)
    }
}
