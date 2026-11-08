package com.rw3.directsync.ble.protocol

import org.junit.Assert.*
import org.junit.Test

class XiaomiDailyTotalsProtocolTest {
    private val engine = XiaomiWearProtocolEngine("0123456789abcdef0123456789abcdef")
    private fun decode(stats: ByteArray): XiaomiWearProtocolEngine.DecodedWatchEvent.RealtimeHeartRate {
        val health = byteArrayOf(0xba.toByte(), 2, stats.size.toByte()) + stats
        return engine.decodeIncomingCommandProto(engine.buildCommandProto(8, 47, 10, health))
            as XiaomiWearProtocolEngine.DecodedWatchEvent.RealtimeHeartRate
    }

    @Test fun dailyTotalsAreRetainedEvenWithoutValidHeartRate() {
        val event = decode(byteArrayOf(8, 0x8e.toByte(), 3, 16, 87, 32, 0))
        assertEquals(398, event.steps); assertEquals(87, event.calories); assertEquals(0, event.bpm)
    }
    @Test fun omittedTotalsAreUnknownRatherThanZero() {
        val event = decode(byteArrayOf(32, 80))
        assertNull(event.steps); assertNull(event.calories); assertEquals(80, event.bpm)
    }
    @Test fun explicitZeroTotalsAreValidAtStartOfDay() {
        val event = decode(byteArrayOf(8, 0, 16, 0))
        assertEquals(0, event.steps); assertEquals(0, event.calories)
    }
}
