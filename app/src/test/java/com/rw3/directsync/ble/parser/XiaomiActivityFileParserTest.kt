package com.rw3.directsync.ble.parser

import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32

class XiaomiActivityFileParserTest {
    private fun file(version: Int, flags: Int, body: ByteArray): ByteArray {
        val bytes = ByteBuffer.allocate(12 + body.size).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(1700000000).put(32).put(version.toByte()).put(flags.toByte()).put(0).put(body).array()
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).putInt(bytes.size - 4,
            CRC32().apply { update(bytes, 0, bytes.size - 4) }.value.toInt())
        return bytes
    }

    @Test fun dailyV3MatchesCapturedLayoutAndValidity() {
        // Same layout and validity mask as the captured RW3 summary; no user timestamps.
        val body = ByteArray(44)
        body[0] = 0xd0.toByte(); body[1] = 12
        ByteBuffer.wrap(body).order(ByteOrder.LITTLE_ENDIAN).putInt(3, 398).putShort(7, 87).putShort(28, 999)
        body[10] = 67; body[25] = 0x80.toByte(); body[26] = 15
        val record = XiaomiActivityFileParser.parse(file(3, 1, body)).single()
        assertEquals(398, record.steps); assertEquals(87, record.calories)
        assertEquals(67, record.restingHeartRate); assertEquals(5, record.standingHours)
        assertNull(record.spo2); assertNull(record.averageHeartRate); assertNull(record.stress)
    }

    @Test fun unreportedValuesStayNullEvenWithPlausibleBytes() {
        val body = ByteArray(44) { 90 }
        body[0] = 0; body[1] = 0; body[2] = 0
        val record = XiaomiActivityFileParser.parse(file(3, 1, body)).single()
        assertNull(record.steps); assertNull(record.calories); assertNull(record.restingHeartRate)
        assertNull(record.standingHours); assertNull(record.spo2)
    }

    @Test fun v5CaloriesUseLaterSlotRatherThanV3Slot() {
        val body = ByteArray(61)
        body[0] = 0xc0.toByte(); body[1] = 4
        ByteBuffer.wrap(body).order(ByteOrder.LITTLE_ENDIAN).putInt(4, 398).putShort(8, 999).putShort(29, 87)
        val record = XiaomiActivityFileParser.parse(file(5, 1, body)).single()
        assertEquals(398, record.steps); assertEquals(87, record.calories)
    }

    @Test fun manualV1CapturesHeartRateAndOxygenSeparately() {
        val body = ByteBuffer.allocate(13).order(ByteOrder.LITTLE_ENDIAN)
            .put(0xe0.toByte()).putInt(1700000000).put(1).put(80)
            .putInt(1700000060).put(2).put(98).array()
        val records = XiaomiActivityFileParser.parse(file(1, 24, body))
        assertEquals(2, records.size); assertEquals("heart_rate", records[0].category)
        assertEquals(80, records[0].averageHeartRate); assertEquals(98, records[1].spo2)
        assertEquals(1700000060000, records[1].timestamp)
    }

    @Test fun manualV2StressAndInvalidZero() {
        val body = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(1700000000).put(0x13).put(35).putInt(1700000060).put(0x12).put(0).array()
        val result = XiaomiActivityFileParser.parse(file(2, 24, body)).single()
        assertEquals(35, result.stress); assertEquals("stress", result.category)
    }

    private fun workout(): ByteArray {
        val body = ByteArray(80)
        body.fill(0xff.toByte(), 0, 5)
        ByteBuffer.wrap(body).order(ByteOrder.LITTLE_ENDIAN).apply {
            putInt(5, 1700000000); putInt(9, 1700001800); putInt(13, 1500)
            putShort(17, 120); put(19, 95); put(20, 130.toByte()); put(21, 70); putShort(67, 308)
        }
        return body
    }

    @Test fun workoutV7ReadsDurationCaloriesAndHeartRate() {
        val record = XiaomiActivityFileParser.parse(file(7, 0xa1, workout())).single()
        assertEquals("workout", record.category); assertEquals(1500, record.activeSeconds)
        assertEquals(120, record.calories); assertEquals(95, record.averageHeartRate)
        assertEquals(130, record.maxHeartRate); assertEquals(70, record.minHeartRate)
        assertEquals(308, record.sportType); assertEquals(1700001800000, record.endTimestamp)
    }

    @Test fun invalidWorkoutDurationRejected() {
        val body = workout(); ByteBuffer.wrap(body).order(ByteOrder.LITTLE_ENDIAN).putInt(13, 99999)
        assertThrows(IllegalArgumentException::class.java) { XiaomiActivityFileParser.parse(file(7, 0xa1, body)) }
    }

    @Test fun corruptedCrcRejected() {
        val bytes = file(3, 1, ByteArray(44)); bytes[15] = 1
        assertThrows(IllegalArgumentException::class.java) { XiaomiActivityFileParser.parse(bytes) }
    }

    @Test fun unsupportedVersionsAndTruncatedManualRejected() {
        assertThrows(IllegalArgumentException::class.java) { XiaomiActivityFileParser.parse(file(6, 1, ByteArray(44))) }
        assertThrows(IllegalArgumentException::class.java) { XiaomiActivityFileParser.parse(file(1, 24, byteArrayOf(0xe0.toByte(), 1))) }
        assertFalse(XiaomiActivityFileParser.accepts(byteArrayOf(0,0,0,0,32,7,0xa0.toByte())))
    }
}
