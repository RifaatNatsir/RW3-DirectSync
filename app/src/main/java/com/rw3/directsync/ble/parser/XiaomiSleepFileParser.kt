package com.rw3.directsync.ble.parser

import com.rw3.directsync.data.db.entity.SleepSessionEntity
import com.rw3.directsync.data.db.entity.SleepStageEntity
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32

/** Xiaomi Wear activity files, not the legacy Huami minute-bucket format. */
object XiaomiSleepFileParser {
    data class Result(val session: SleepSessionEntity, val stages: List<SleepStageEntity>)

    fun isSleepFile(id: ByteArray): Boolean {
        if (id.size != 7) return false
        val flags = id[6].toInt() and 255
        return flags and 128 == 0 && ((flags shr 2) in listOf(3, 8))
    }

    fun parse(file: ByteArray): Result {
        require(file.size >= 13) { "File tidur terlalu pendek" }
        val crc = CRC32().apply { update(file, 0, file.size - 4) }.value
        val expected = ByteBuffer.wrap(file, file.size - 4, 4).order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xffffffffL
        require(crc == expected) { "Checksum file tidur tidak sesuai" }
        val id = file.copyOfRange(0, 7)
        require(isSleepFile(id)) { "File bukan data tidur" }
        require(file[7] == 0.toByte()) { "Header file tidur tidak dikenal" }
        val source = id.joinToString("") { "%02x".format(it.toInt() and 255) }
        val version = id[5].toInt() and 255
        val subtype = (id[6].toInt() and 127) shr 2
        val b = ByteBuffer.wrap(file, 8, file.size - 12).slice().order(ByteOrder.LITTLE_ENDIAN)
        return if (subtype == 3) parseStages(b, version, source) else parseDetails(b, version, source)
    }

    private fun ByteBuffer.u8(): Int { require(remaining() >= 1) { "File tidur terpotong" }; return get().toInt() and 255 }
    private fun ByteBuffer.u16(): Int { require(remaining() >= 2) { "File tidur terpotong" }; return short.toInt() and 65535 }
    private fun ByteBuffer.seconds(): Long { require(remaining() >= 4) { "Timestamp terpotong" }; return (int.toLong() and 0xffffffffL) * 1000L }
    private fun ByteBuffer.skip(n: Int) { require(n >= 0 && n <= remaining()) { "File tidur terpotong" }; position(position() + n) }

    private fun parseStages(b: ByteBuffer, version: Int, source: String): Result {
        require(version == 2) { "Versi sleep stages belum didukung: $version" }
        b.skip(7)
        val total = b.u16()
        val start = b.seconds()
        val end = b.seconds()
        b.skip(3)
        val deep = b.u16(); val light = b.u16(); val rem = b.u16(); val awake = b.u16()
        b.skip(1)
        require(b.remaining() % 5 == 0) { "Daftar fase tidur terpotong" }
        val changes = mutableListOf<Pair<Long, Int>>()
        while (b.hasRemaining()) changes += b.seconds() to b.u8()
        val stages = changes.zipWithNext().mapNotNull { (a, z) ->
            stage(a.first.coerceAtLeast(start), z.first.coerceAtMost(end), a.second, source, legacy = true)
        }
        return Result(session(start, end, total, deep, light, rem, awake, null, null, source), stages)
    }

    private fun parseDetails(b: ByteBuffer, version: Int, source: String): Result {
        require(version in 1..4) { "Versi sleep details belum didukung: $version" }
        val flags = b.u8()
        val unfinished = b.u8() == 1
        val start = b.seconds(); val end = b.seconds()
        val score = if (version >= 4) b.u8().takeIf { it in 1..100 } else null
        val shift = if (version >= 4) 1 else 0
        var averageHr: Int? = null
        for ((bit, width) in listOf((5 - shift) to 1, (4 - shift) to 1, (3 - shift) to 4)) {
            if (width == 4 && version < 3) continue
            if (flags and (1 shl bit) == 0) continue
            b.u16() // sample interval
            val count = b.u16()
            if (count > 0) {
                if (version >= 2) b.skip(4)
                if (bit == 5 - shift) {
                    val heartRates = List(count) { b.u8() }.filter { it in 30..240 }
                    if (heartRates.isNotEmpty()) averageHr = (heartRates.average() + 0.5).toInt()
                } else b.skip(count * width)
            }
        }
        var totals: List<Int>? = null
        val stages = mutableListOf<SleepStageEntity>()
        while (b.remaining() >= 17) {
            if (b.get(b.position()) != 0xfb.toByte() || b.get(b.position() + 1) != 0xfa.toByte() ||
                b.get(b.position() + 2) != 0xfc.toByte() || b.get(b.position() + 3) != 0xff.toByte()) {
                b.skip(1); continue
            }
            b.skip(4)
            require(b.u8() == 17) { "Header fase tidur tidak dikenal" }
            val timestamp = b.long
            b.u8() // parity
            val type = b.u8()
            val size = (b.u8() shl 8) or b.u8()
            if (type in listOf(2, 3, 9, 12, 13, 14, 15)) continue
            require(size <= b.remaining()) { "Paket fase tidur terpotong" }
            val body = b.slice().order(ByteOrder.BIG_ENDIAN).apply { limit(size) }
            b.skip(size)
            when (type) {
                16 -> {
                    require(size >= 13) { "Ringkasan tidur terpotong" }
                    body.u8()
                    totals = List(5) { body.u16() } // total, awake, light, REM, deep
                }
                17 -> {
                    require(size % 2 == 0 && timestamp > 0) { "Paket fase tidur tidak valid" }
                    var time = timestamp * 1000L
                    while (body.hasRemaining()) {
                        val value = body.u16()
                        val next = time + (value and 4095) * 60_000L
                        stage(time.coerceAtLeast(start), next.coerceAtMost(end), value shr 12, source, legacy = false)?.let(stages::add)
                        time = next
                    }
                }
            }
        }
        require(!unfinished) { "Sesi tidur di jam belum selesai" }
        val t = requireNotNull(totals) { "Ringkasan tidur jam belum ditemukan" }
        return Result(session(start, end, t[0], t[4], t[2], t[3], t[1], score, averageHr, source), stages)
    }

    private fun session(start: Long, end: Long, total: Int, deep: Int, light: Int, rem: Int, awake: Int,
                        score: Int?, hr: Int?, source: String): SleepSessionEntity {
        require(start > 0 && end > start && end - start <= 48 * 3600_000L) { "Rentang waktu tidur tidak valid" }
        require(total in 1..2880 && total * 60_000L <= end - start + 60_000L) { "Durasi tidur tidak valid" }
        require(listOf(deep, light, rem, awake).all { it in 0..2880 }) { "Durasi fase tidur tidak valid" }
        return SleepSessionEntity(start, end, total, deep, light, rem, awake, score, hr, source)
    }

    private fun stage(start: Long, end: Long, raw: Int, source: String, legacy: Boolean): SleepStageEntity? {
        val name = if (legacy) when (raw) { 2 -> "DEEP"; 3 -> "LIGHT"; 4 -> "REM"; 5 -> "AWAKE"; else -> null }
        else when (raw) { 0 -> "AWAKE"; 1 -> "LIGHT"; 2 -> "DEEP"; 3 -> "REM"; else -> null }
        if (name == null || end <= start) return null
        return SleepStageEntity(startTimestamp = start, endTimestamp = end, stage = name,
            durationMinutes = ((end - start) / 60_000L).toInt(), rawType = raw, sourceFileId = source)
    }
}
