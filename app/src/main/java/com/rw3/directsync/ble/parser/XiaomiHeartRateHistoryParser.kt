package com.rw3.directsync.ble.parser

import com.rw3.directsync.data.db.entity.HeartRateEntity
import com.rw3.directsync.data.db.entity.ActivityRecordEntity
import com.rw3.directsync.data.db.entity.DailyActivityMinuteEntity
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32

/** Daily detail files contain one record per minute, including measurements made offline. */
object XiaomiHeartRateHistoryParser {
    data class DailyDetails(val heartRates: List<HeartRateEntity>, val summary: ActivityRecordEntity,
                            val minutes: List<DailyActivityMinuteEntity>)
    fun accepts(id: ByteArray) = id.size == 7 && id[6] == 0.toByte()

    fun parse(file: ByteArray): List<HeartRateEntity> = parseDailyDetails(file).heartRates

    fun parseDailyDetails(file: ByteArray): DailyDetails {
        require(file.size >= 16) { "File riwayat HR terpotong" }
        require(accepts(file.copyOfRange(0, 7))) { "Bukan file detail harian" }
        val b = ByteBuffer.wrap(file).order(ByteOrder.LITTLE_ENDIAN)
        val crc = b.getInt(file.size - 4).toLong() and 0xffffffffL
        require(CRC32().apply { update(file, 0, file.size - 4) }.value == crc) { "Checksum riwayat HR tidak sesuai" }
        val start = (b.int.toLong() and 0xffffffffL) * 1000
        require(start > 0 && file[7] == 0.toByte()) { "Header riwayat HR tidak valid" }
        val version = file[5].toInt() and 255
        val headerSize = when (version) {
            1, 2 -> 4
            3 -> 5
            4 -> 6
            else -> throw IllegalArgumentException("Versi riwayat HR belum didukung: $version")
        }
        require(file.size >= 12 + headerSize) { "Header riwayat HR terpotong" }
        b.position(8)
        b.limit(file.size - 4)
        val header = ByteArray(headerSize).also { b.get(it) }
        val result = mutableListOf<HeartRateEntity>()
        val minuteRecords = mutableListOf<DailyActivityMinuteEntity>()
        val source = file.copyOfRange(0, 7).joinToString("") { "%02x".format(it.toInt() and 255) }
        var minute = 0
        var totalSteps: Int? = null
        var totalCalories: Int? = null
        while (b.hasRemaining()) {
            require(minute < 7 * 24 * 60) { "Riwayat HR terlalu panjang" }
            val before = b.position()
            var group = 0
            // Each nibble carries presence (bit 3) and field validity (bits 2..0).
            fun read(width: Int): Pair<Int, Int> {
                require(group < headerSize * 2) { "Bitmap riwayat HR terpotong" }
                val value = header[group / 2].toInt() and 255
                val flags = if (group++ % 2 == 0) value shr 4 else value and 15
                if (flags and 8 == 0) return flags to 0
                require(b.remaining() >= width) { "Sampel riwayat HR terpotong" }
                return flags to if (width == 2) b.short.toInt() and 65535 else b.get().toInt() and 255
            }
            val (stepFlags, steps) = read(2)
            val extra = stepFlags and 8 != 0 && stepFlags and 2 != 0 && steps and 0x4000 != 0
            val (calorieFlags, activity) = read(1)
            // Per-minute increments: 14-bit steps and the low 6 bits of the activity byte.
            if (stepFlags and 9 == 9) totalSteps = (totalSteps ?: 0) + (steps and 0x3fff)
            if (calorieFlags and 10 == 10) totalCalories = (totalCalories ?: 0) + (activity and 0x3f)
            read(1); read(2)
            val (hrFlags, bpm) = read(1)
            read(1); read(2)
            if (version >= 3) { read(1); read(1) }
            if (extra) read(1)
            if (version >= 4) { read(2); read(2) }
            require(b.position() > before) { "Bitmap riwayat HR tanpa sampel" }
            val timestamp = start + minute++ * 60_000L
            val minuteSteps = (steps and 0x3fff).takeIf { stepFlags and 9 == 9 }
            val minuteCalories = (activity and 0x3f).takeIf { calorieFlags and 10 == 10 }
            if (minuteSteps != null || minuteCalories != null) {
                minuteRecords += DailyActivityMinuteEntity(timestamp, minuteSteps, minuteCalories, source)
            }
            if (hrFlags and 12 == 12 && bpm in 30..240) {
                // Live samples use positive auto IDs. Historical samples have stable negative IDs,
                // so re-downloading a file cannot duplicate or reset webhook acknowledgements.
                result += HeartRateEntity(id = -timestamp, timestamp = timestamp, bpm = bpm)
            }
        }
        return DailyDetails(result, ActivityRecordEntity(id = "daily-details:$source", category = "daily",
            timestamp = start + (minute - 1).coerceAtLeast(0) * 60_000L,
            steps = totalSteps, calories = totalCalories,
            averageHeartRate = result.takeIf { it.isNotEmpty() }?.map { it.bpm }?.average()?.toInt(),
            minHeartRate = result.minOfOrNull { it.bpm }, maxHeartRate = result.maxOfOrNull { it.bpm },
            sourceFileId = source, formatVersion = version), minuteRecords)
    }
}
