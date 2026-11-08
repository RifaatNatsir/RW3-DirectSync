package com.rw3.directsync.ble.parser

import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32

class XiaomiHeartRateHistoryParserTest {
    private fun file(version: Int, header: ByteArray, samples: ByteArray): ByteArray {
        val bytes = ByteBuffer.allocate(12 + header.size + samples.size).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(1700000000).put(32).put(version.toByte()).put(0).put(0)
            .put(header).put(samples).array()
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).putInt(bytes.size - 4,
            CRC32().apply { update(bytes, 0, bytes.size - 4) }.value.toInt())
        return bytes
    }

    @Test fun minuteSamplesPreserveOfflineTimeAndSkipInvalidReadings() {
        val bytes = file(1, byteArrayOf(0, 0, 0xc0.toByte(), 0), byteArrayOf(72, 0, 255.toByte(), 81))
        val samples = XiaomiHeartRateHistoryParser.parse(bytes)
        assertEquals(listOf(72, 81), samples.map { it.bpm })
        assertEquals(listOf(1700000000000, 1700000180000), samples.map { it.timestamp })
        assertEquals(samples, XiaomiHeartRateHistoryParser.parse(bytes))
        assertTrue(samples.all { it.id == -it.timestamp && !it.syncedToWebhook })
    }

    @Test fun absentValidityBitDoesNotCreateHeartRate() {
        assertTrue(XiaomiHeartRateHistoryParser.parse(file(2, byteArrayOf(0, 0, 0x80.toByte(), 0), byteArrayOf(75))).isEmpty())
    }

    @Test fun v3SkipsOtherPresentGroupsAndOptionalEntryWithoutLosingAlignment() {
        val header = byteArrayOf(0xa8.toByte(), 0x88.toByte(), 0xc8.toByte(), 0x88.toByte(), 0x88.toByte())
        val sample = ByteBuffer.allocate(13).order(ByteOrder.LITTLE_ENDIAN)
            .putShort(0x4000).put(5).put(7).putShort(100).put(77).put(9).putShort(222)
            .put(98).put(33).put(11).array()
        val samples = XiaomiHeartRateHistoryParser.parse(file(3, header, sample + sample))
        assertEquals(listOf(77, 77), samples.map { it.bpm })
        assertEquals(60000L, samples[1].timestamp - samples[0].timestamp)
    }

    @Test fun v4ConsumesLightAndMomentum() {
        val samples = XiaomiHeartRateHistoryParser.parse(file(4,
            byteArrayOf(0, 0, 0xc0.toByte(), 0, 8, 0x88.toByte()),
            byteArrayOf(88, 1, 2, 3, 4, 99, 1, 2, 3, 4)))
        assertEquals(listOf(88, 99), samples.map { it.bpm })
    }

    @Test fun rw3DailyBitmapKeepsMinuteAlignment() {
        // Layout observed on RW3; the sample values and timestamp are synthetic.
        val sample = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
            .putShort(5).put(1).put(2).putShort(200).put(73).put(30).array()
        val records = XiaomiHeartRateHistoryParser.parse(file(3,
            byteArrayOf(0x9a.toByte(), 0xcc.toByte(), 0xc0.toByte(), 0, 0xc0.toByte()), sample + sample))
        assertEquals(listOf(73, 73), records.map { it.bpm })
        assertEquals(60000L, records[1].timestamp - records[0].timestamp)
    }

    @Test fun dailyTotalsUseValidMinuteIncrementsWithoutIncludingActivityTypeBits() {
        val samples = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN)
            .putShort(100).put((0x80 or 30).toByte()).put(0).putShort(0).put(73).put(0)
            .putShort(298).put((0x40 or 57).toByte()).put(0).putShort(0).put(79).put(0).array()
        val parsed = XiaomiHeartRateHistoryParser.parseDailyDetails(file(3,
            byteArrayOf(0x9a.toByte(), 0xcc.toByte(), 0xc0.toByte(), 0, 0xc0.toByte()), samples))
        assertEquals(398, parsed.summary.steps); assertEquals(87, parsed.summary.calories)
        assertEquals(2, parsed.heartRates.size)
        assertEquals(1700000060000L, parsed.summary.timestamp)
    }

    @Test fun absentDailyTotalsStayUnknownWhileExplicitZerosRemainValid() {
        val absent = XiaomiHeartRateHistoryParser.parseDailyDetails(file(1,
            byteArrayOf(0,0,0xc0.toByte(),0), byteArrayOf(80)))
        assertNull(absent.summary.steps); assertNull(absent.summary.calories)
        val zero = XiaomiHeartRateHistoryParser.parseDailyDetails(file(1,
            byteArrayOf(0x9a.toByte(),0,0,0), byteArrayOf(0,0,0)))
        assertEquals(0, zero.summary.steps); assertEquals(0, zero.summary.calories)
    }

    @Test fun truncatedUnknownAndCorruptFilesRejected() {
        val good = file(1, byteArrayOf(0, 0, 0xc0.toByte(), 0), byteArrayOf(80))
        val corrupt = good.copyOf().also { it[12] = 81 }
        assertThrows(IllegalArgumentException::class.java) { XiaomiHeartRateHistoryParser.parse(corrupt) }
        assertThrows(IllegalArgumentException::class.java) { XiaomiHeartRateHistoryParser.parse(good.copyOf(10)) }
        assertThrows(IllegalArgumentException::class.java) { XiaomiHeartRateHistoryParser.parse(file(5, ByteArray(6), byteArrayOf(80))) }
        assertThrows(IllegalArgumentException::class.java) {
            XiaomiHeartRateHistoryParser.parse(file(1, byteArrayOf(0x80.toByte(), 0, 0xc0.toByte(), 0), byteArrayOf(1)))
        }
        assertThrows(IllegalArgumentException::class.java) {
            XiaomiHeartRateHistoryParser.parse(file(1, ByteArray(4), byteArrayOf(1)))
        }
    }

    @Test fun onlyDailyDetailsAreAccepted() {
        assertTrue(XiaomiHeartRateHistoryParser.accepts(byteArrayOf(0,0,0,0,32,1,0)))
        for (flags in listOf(1, 12, 24, 32, 0xa0)) {
            assertFalse(XiaomiHeartRateHistoryParser.accepts(byteArrayOf(0,0,0,0,32,1,flags.toByte())))
        }
    }
}
