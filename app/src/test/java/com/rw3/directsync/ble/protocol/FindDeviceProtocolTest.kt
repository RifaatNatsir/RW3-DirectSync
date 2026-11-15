package com.rw3.directsync.ble.protocol

import org.junit.Assert.*
import org.junit.Test

class FindDeviceProtocolTest {
    private val engine = XiaomiWearProtocolEngine("00".repeat(16))
    @Test fun commandsUseTheSystemFieldAndExplicitStartStopValues() {
        assertArrayEquals(byteArrayOf(8, 2, 16, 18, 34, 2, 40, 0), engine.buildFindDeviceCommand(false, true))
        assertArrayEquals(byteArrayOf(8, 2, 16, 18, 34, 2, 40, 1), engine.buildFindDeviceCommand(false, false))
        assertArrayEquals(byteArrayOf(8, 2, 16, 17, 34, 2, 40, 1), engine.buildFindDeviceCommand(true, false))
    }
    @Test fun watchRequestsStartAndStopPhoneWithoutConfusingAnAck() {
        assertEquals(XiaomiWearProtocolEngine.DecodedWatchEvent.FindPhone(true), engine.decodeIncomingCommandProto(byteArrayOf(8,2,16,17,34,2,40,0)))
        assertEquals(XiaomiWearProtocolEngine.DecodedWatchEvent.FindPhone(false), engine.decodeIncomingCommandProto(byteArrayOf(8,2,16,17,34,2,40,1)))
        assertTrue(engine.decodeIncomingCommandProto(byteArrayOf(8,2,16,17)) is XiaomiWearProtocolEngine.DecodedWatchEvent.RawCommand)
        assertEquals(XiaomiWearProtocolEngine.DecodedWatchEvent.FindPhone(true), engine.decodeIncomingCommandProto(byteArrayOf(8,2,16,17,34,0)))
        assertTrue(engine.decodeIncomingCommandProto(byteArrayOf(8,2,16,17,34,2,40,2)) is XiaomiWearProtocolEngine.DecodedWatchEvent.RawCommand)
    }
    @Test fun watchDismissalDoesNotStartPhoneAlarm() {
        assertTrue(engine.decodeIncomingCommandProto(byteArrayOf(8,2,16,18,34,0)) is XiaomiWearProtocolEngine.DecodedWatchEvent.RawCommand)
        assertEquals(XiaomiWearProtocolEngine.DecodedWatchEvent.FindWatch(false), engine.decodeIncomingCommandProto(byteArrayOf(8,2,16,18,34,2,40,1)))
    }
}
