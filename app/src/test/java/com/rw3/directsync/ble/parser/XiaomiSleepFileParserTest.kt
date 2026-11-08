package com.rw3.directsync.ble.parser

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32
import org.junit.Assert.*
import org.junit.Test

class XiaomiSleepFileParserTest {
    @Test fun acceptsWatchPushWithoutPriorFileRequest() {
        val file = stagesFile()
        val transfer = XiaomiActivityTransfer()
        val complete = transfer.accept(byteArrayOf(1, 0, 1, 0) + file)
        assertNotNull(complete)
        assertEquals(449, XiaomiSleepFileParser.parse(complete!!).session.totalMinutes)
    }

    private val start = 1_794_154_560L
    private val end = start + 449 * 60

    private fun wrap(body: ByteArray, subtype: Int = 3, version: Int = 2): ByteArray {
        val bytes = ByteBuffer.allocate(8 + body.size + 4).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(start.toInt()).put(32).put(version.toByte()).put((subtype shl 2).toByte()).put(0)
            .put(body).array()
        val crc = CRC32().apply { update(bytes, 0, bytes.size - 4) }.value
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).putInt(bytes.size - 4, crc.toInt())
        return bytes
    }

    private fun stagesFile(): ByteArray {
        val body = ByteBuffer.allocate(7 + 2 + 8 + 3 + 8 + 1 + 15).order(ByteOrder.LITTLE_ENDIAN)
            .put(ByteArray(7)).putShort(449).putInt(start.toInt()).putInt(end.toInt()).put(ByteArray(3))
            .putShort(90).putShort(300).putShort(59).putShort(0).put(0)
            .putInt(start.toInt()).put(3)
            .putInt((start + 300 * 60).toInt()).put(2)
            .putInt(end.toInt()).put(0)
        return wrap(body.array())
    }

    @Test fun readsWatchTotalsAndActualTimestamps() {
        val parsed = XiaomiSleepFileParser.parse(stagesFile())
        assertEquals(449, parsed.session.totalMinutes)
        assertEquals(start * 1000L, parsed.session.startTimestamp)
        assertEquals(end * 1000L, parsed.session.endTimestamp)
        assertEquals(90, parsed.session.deepMinutes)
        assertEquals(59, parsed.session.remMinutes)
        assertNotNull(parsed.stages.first().sourceFileId)
        assertNull(parsed.session.sleepScore)
    }

    @Test(expected = IllegalArgumentException::class) fun rejectsCorruptChecksum() {
        val file = stagesFile(); file[20] = (file[20].toInt() xor 1).toByte()
        XiaomiSleepFileParser.parse(file)
    }

    @Test(expected = IllegalArgumentException::class) fun doesNotReadDailyActivityAsSleep() {
        XiaomiSleepFileParser.parse(wrap(ByteArray(50), subtype = 0))
    }

    private fun packet(type: Int, data: ByteArray): ByteArray =
        ByteBuffer.allocate(17 + data.size).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(0xfffcfafb.toInt()).put(17).putLong(start).put(0).put(type.toByte())
            .put((data.size shr 8).toByte()).put(data.size.toByte()).put(data).array()

    @Test fun readsDetailsSummaryAndPackedStages() {
        val summary = ByteBuffer.allocate(13).order(ByteOrder.BIG_ENDIAN)
            .put(0).putShort(449).putShort(0).putShort(300).putShort(59).putShort(90).put(0).put(0).array()
        val stages = ByteBuffer.allocate(6).order(ByteOrder.BIG_ENDIAN)
            .putShort(((1 shl 12) or 300).toShort())
            .putShort(((2 shl 12) or 90).toShort())
            .putShort(((3 shl 12) or 59).toShort()).array()
        val header = ByteBuffer.allocate(10).order(ByteOrder.LITTLE_ENDIAN)
            .put(0).put(0).putInt(start.toInt()).putInt(end.toInt()).array()
        val parsed = XiaomiSleepFileParser.parse(wrap(header + packet(16, summary) + packet(17, stages), 8))
        assertEquals(449, parsed.session.totalMinutes)
        assertEquals(listOf("LIGHT", "DEEP", "REM"), parsed.stages.map { it.stage })
        assertEquals(end * 1000L, parsed.stages.last().endTimestamp)
    }

    private fun sensorEvent(type: Int, timestamp: Long): ByteArray =
        ByteBuffer.allocate(17).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(0xfffcfafb.toInt()).put(17).putLong(timestamp).put(0)
            .put(type.toByte()).putShort(0).array()

    private fun closedSensorFile(wake: Long = end * 1000): ByteArray {
        val header = ByteBuffer.allocate(11).order(ByteOrder.LITTLE_ENDIAN)
            .put(0x80.toByte()).put(1).putInt(start.toInt()).putInt(end.toInt()).put(0).array()
        return wrap(header + sensorEvent(2, start * 1000) + sensorEvent(3, wake), 8, 4)
    }

    @Test fun rw3ClosedSensorRecordingDoesNotGetRejectedByAmbiguousFinishFlag() {
        val parsed = XiaomiSleepFileParser.parse(closedSensorFile())
        assertEquals(449, parsed.session.totalMinutes)
        assertEquals(start * 1000, parsed.session.startTimestamp)
        assertEquals(end * 1000, parsed.session.endTimestamp)
        assertFalse(parsed.session.hasStageData)
        assertTrue(parsed.stages.isEmpty())
        assertNull(parsed.session.sleepScore)
        assertTrue(parsed.session.sourceFileId.startsWith("recording:"))
    }

    @Test fun rawRecordingWithoutMatchingWakeRemainsUnverified() {
        assertThrows(IllegalArgumentException::class.java) {
            XiaomiSleepFileParser.parse(closedSensorFile(end * 1000 - 60_000))
        }
    }

    @Test fun closedEventsAlsoAcceptSecondTimestamps() {
        val header = ByteBuffer.allocate(11).order(ByteOrder.LITTLE_ENDIAN)
            .put(0).put(1).putInt(start.toInt()).putInt(end.toInt()).put(0).array()
        val parsed = XiaomiSleepFileParser.parse(wrap(header + sensorEvent(2, start) + sensorEvent(3, end), 8, 4))
        assertEquals(449, parsed.session.totalMinutes)
    }

    @Test fun transferCompletesOnlyOnce() {
        val file = stagesFile()
        val transfer = XiaomiActivityTransfer()
        transfer.begin(file.copyOfRange(0, 7))
        val part1 = byteArrayOf(2, 0, 1, 0) + file.copyOfRange(0, 20)
        val part2 = byteArrayOf(2, 0, 2, 0) + file.copyOfRange(20, file.size)
        assertNull(transfer.accept(part1))
        assertNull(transfer.accept(part1))
        assertArrayEquals(file, transfer.accept(part2))
        assertEquals(0, transfer.bytesBuffered)
    }

    @Test(expected = IllegalArgumentException::class) fun transferRejectsMissingChunks() {
        val file = stagesFile()
        val transfer = XiaomiActivityTransfer()
        transfer.begin(file.copyOfRange(0, 7))
        transfer.accept(byteArrayOf(2, 0, 2, 0) + file)
    }

    @Test(expected = IllegalArgumentException::class) fun transferRejectsImpossibleHeaderFromEncryptedBytes() {
        val file = stagesFile()
        val transfer = XiaomiActivityTransfer()
        transfer.begin(file.copyOfRange(0, 7))
        transfer.accept(byteArrayOf(0xc8.toByte(), 0x94.toByte(), 0x32, 0xee.toByte()) + file)
    }
}
