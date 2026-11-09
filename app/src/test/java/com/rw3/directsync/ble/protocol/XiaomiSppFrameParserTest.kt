package com.rw3.directsync.ble.protocol

import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class XiaomiSppFrameParserTest {
    private fun v1(payload: ByteArray) = ByteBuffer.allocate(11 + payload.size).order(ByteOrder.LITTLE_ENDIAN)
        .put(byteArrayOf(0xBA.toByte(), 0xDC.toByte(), 0xFE.toByte(), 3, 0x80.toByte()))
        .putShort((payload.size + 3).toShort()).put(2).put(7).put(1).put(payload).put(0xEF.toByte()).array()
    private fun v2(payload: ByteArray) = ByteBuffer.allocate(8 + payload.size).order(ByteOrder.LITTLE_ENDIAN)
        .put(byteArrayOf(0xA5.toByte(), 0xA5.toByte(), 3, 9)).putShort(payload.size.toShort()).putShort(0).put(payload).array()
    private fun decode(reads: List<ByteArray>): List<ByteArray> {
        val packets = mutableListOf<ByteArray>(); var pending = ByteArray(0)
        for (read in reads) pending = XiaomiSppFrameParser.consume(pending + read,
            { channel, type, body -> assertEquals(3, channel); assertEquals(1, type); packets.add(body) },
            { type, seq, body -> assertEquals(3, type); assertEquals(9, seq); packets.add(body) })
        assertEquals(0, pending.size)
        return packets
    }
    @Test fun allV1AndV2ReadBoundariesPreserveEntireFrameIncludingPreamble() {
        val payload = ByteArray(97) { it.toByte() }
        for (frame in listOf(v1(payload), v2(payload))) for (split in 0..frame.size) {
            val packets = decode(listOf(frame.copyOfRange(0, split), frame.copyOfRange(split, frame.size)))
            assertEquals("split=$split", 1, packets.size)
            assertArrayEquals(payload, packets.single())
        }
    }
    @Test fun successiveFilesSurviveOneByteReadsAndCoalescedFrames() {
        val payloads = listOf(byteArrayOf(1, 2, 3), ByteArray(2800) { (it * 17).toByte() }, byteArrayOf(4, 5))
        val wire = v1(payloads[0]) + v2(payloads[1]) + v2(payloads[2])
        for (reads in listOf(listOf(wire), wire.map { byteArrayOf(it) }, wire.toList().chunked(2048).map { it.toByteArray() })) {
            val packets = decode(reads)
            assertEquals(3, packets.size)
            payloads.zip(packets).forEach { (expected, actual) -> assertArrayEquals(expected, actual) }
        }
    }
    @Test fun noiseAndInvalidV1LengthDoNotBlockFollowingFrame() {
        val invalid = v1(byteArrayOf(1)).apply { this[5] = 0; this[6] = 0 }
        val packets = decode(listOf(byteArrayOf(0, 3, 0xBA.toByte(), 0) + invalid + v2(byteArrayOf(7))))
        assertArrayEquals(byteArrayOf(7), packets.single())
    }
}
