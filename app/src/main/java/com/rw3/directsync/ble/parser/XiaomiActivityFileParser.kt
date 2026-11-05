package com.rw3.directsync.ble.parser

import com.rw3.directsync.data.db.entity.ActivityRecordEntity
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32

/** Xiaomi activity wire formats. Never infer a metric from bytes whose validity bit is clear. */
object XiaomiActivityFileParser {
    fun accepts(id: ByteArray): Boolean {
        if (id.size != 7) return false
        val flags = id[6].toInt() and 255
        val subtype = (flags and 127) shr 2
        return if (flags and 128 != 0) flags and 3 == 1 && subtype == 8
        else (subtype == 0 && flags and 3 == 1) || (subtype == 6 && flags and 3 == 0)
    }

    fun parse(file: ByteArray): List<ActivityRecordEntity> {
        require(file.size >= 13) { "File aktivitas terpotong" }
        val b = ByteBuffer.wrap(file).order(ByteOrder.LITTLE_ENDIAN)
        val expected = b.getInt(file.size - 4).toLong() and 0xffffffffL
        require(CRC32().apply { update(file, 0, file.size - 4) }.value == expected) { "Checksum aktivitas tidak sesuai" }
        val id = file.copyOfRange(0, 7)
        require(accepts(id)) { "Jenis aktivitas belum didukung" }
        require(file[7] == 0.toByte()) { "Header aktivitas tidak dikenal" }
        val source = id.joinToString("") { "%02x".format(it.toInt() and 255) }
        val version = id[5].toInt() and 255
        val timestamp = (b.getInt(0).toLong() and 0xffffffffL) * 1000
        val body = ByteBuffer.wrap(file, 8, file.size - 12).slice().order(ByteOrder.LITTLE_ENDIAN)
        return when {
            id[6].toInt() and 128 != 0 -> listOf(workout(body, version, source))
            (id[6].toInt() and 127) shr 2 == 6 -> manual(body, version, source)
            else -> listOf(daily(body, version, source, timestamp))
        }
    }

    private fun daily(b: ByteBuffer, version: Int, source: String, timestamp: Long): ActivityRecordEntity {
        require(version in 3..5) { "Versi aktivitas harian belum didukung: $version" }
        val widths = intArrayOf(4,2,1,1,1,4,1,4,1,1,1,1,3,2,2,1,1,4,1,4,1,
            2,2,1,1,1,1,2,1,1,2,2)
        val count = if (version == 5) 32 else 21
        val header = ByteArray(if (version == 5) 4 else 3).also { b.get(it) }
        require(b.remaining() == widths.take(count).sum()) { "Panjang ringkasan harian tidak sesuai" }
        val values = arrayOfNulls<Int>(count)
        repeat(count) { slot ->
            var value = 0
            repeat(widths[slot]) { i -> value = value or ((b.get().toInt() and 255) shl (i * 8)) }
            if (valid(header, slot)) values[slot] = value
        }
        return ActivityRecordEntity(id = "daily:$source", category = "daily", timestamp = timestamp,
            steps = values[0]?.takeIf { it >= 0 }, calories = values[1],
            restingHeartRate = hr(values[3]), averageHeartRate = hr(values[8]),
            minHeartRate = hr(values[6]), maxHeartRate = hr(values[4]),
            stress = values[9]?.takeIf { it in 1..100 }, spo2 = oxygen(values[20]),
            standingHours = values[12]?.let { Integer.bitCount(it and 0xffffff) },
            sourceFileId = source, formatVersion = version)
    }

    private fun manual(b: ByteBuffer, version: Int, source: String): List<ActivityRecordEntity> {
        require(version in 1..2) { "Versi pengukuran manual belum didukung: $version" }
        if (version == 1) {
            // RW3 uses a presence bitmap (e0); other devices use ff. Both have six-byte records.
            require(b.hasRemaining() && (b.get().toInt() and 255) in listOf(0xe0, 0xff)) { "Header pengukuran tidak dikenal" }
        }
        require(b.remaining() > 0 && b.remaining() % 6 == 0) { "Pengukuran manual terpotong" }
        val result = mutableListOf<ActivityRecordEntity>()
        while (b.hasRemaining()) {
            val timestamp = seconds(b)
            val wireType = b.get().toInt() and 255
            val type = if (version == 2) wireType - 0x10 else wireType
            require(type in 1..3) { "Jenis pengukuran belum didukung: $wireType" }
            val value = b.get().toInt() and 255
            val category = when (type) { 1 -> "heart_rate"; 2 -> "spo2"; else -> "stress" }
            val checked = when (type) { 1 -> hr(value); 2 -> oxygen(value); else -> value.takeIf { it in 1..100 } }
            if (checked != null) result += ActivityRecordEntity(id = "$category:$timestamp", category = category,
                timestamp = timestamp, averageHeartRate = if (type == 1) checked else null,
                spo2 = if (type == 2) checked else null, stress = if (type == 3) checked else null,
                sourceFileId = source, formatVersion = version)
        }
        return result
    }

    private fun workout(b: ByteBuffer, version: Int, source: String): ActivityRecordEntity {
        require(version in listOf(5,7,8,9,10)) { "Versi olahraga belum didukung: $version" }
        val headerSize = when (version) { 5 -> 3; 7 -> 5; else -> 6 }
        val minimumBody = when (version) { 5 -> 64; 7 -> 75; 10 -> 98; else -> 91 }
        require(b.remaining() >= headerSize + minimumBody) { "Ringkasan olahraga terpotong" }
        val header = ByteArray(headerSize).also { b.get(it) }
        val start = seconds(b); val end = seconds(b)
        val active = b.int; val calories = b.short.toInt() and 65535
        val avg = b.get().toInt() and 255; val max = b.get().toInt() and 255; val min = b.get().toInt() and 255
        require(valid(header, 0) && valid(header, 1) && start > 0 && end >= start && end - start <= 7 * 86400000L) { "Waktu olahraga tidak valid" }
        require(!valid(header, 2) || active.toLong() in 0..((end - start) / 1000)) { "Durasi olahraga tidak valid" }
        // The remaining fields differ by firmware. Read the sport code only at its defined offset.
        val typeOffset = if (version == 5) 54 else 62
        require(b.limit() >= headerSize + typeOffset + 2) { "Detail olahraga terpotong" }
        val sport = b.getShort(headerSize + typeOffset).toInt() and 65535
        return ActivityRecordEntity(id = "workout:$source", category = "workout", timestamp = start,
            endTimestamp = end, activeSeconds = active.takeIf { valid(header, 2) },
            calories = calories.takeIf { valid(header, 3) }, averageHeartRate = hr(avg).takeIf { valid(header, 4) },
            maxHeartRate = hr(max).takeIf { valid(header, 5) }, minHeartRate = hr(min).takeIf { valid(header, 6) },
            sportType = sport.takeIf { valid(header, if (version == 5) 16 else 21) }, sourceFileId = source, formatVersion = version)
    }

    private fun valid(header: ByteArray, slot: Int) = header[slot / 8].toInt() and (1 shl (7 - slot % 8)) != 0
    private fun seconds(b: ByteBuffer) = (b.int.toLong() and 0xffffffffL) * 1000
    private fun hr(value: Int?) = value?.takeIf { it in 30..240 }
    private fun oxygen(value: Int?) = value?.takeIf { it in 1..100 }
}
