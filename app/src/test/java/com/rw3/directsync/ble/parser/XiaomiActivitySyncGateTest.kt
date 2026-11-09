package com.rw3.directsync.ble.parser

import org.junit.Assert.*
import org.junit.Test

class XiaomiActivitySyncGateTest {
    @Test fun lostListResponseIsReportedWithoutTreatingAnEmptyListAsAFailure() {
        val gate = XiaomiActivitySyncGate(100, 500)
        gate.start(0); gate.takeRequest(100); gate.onList(1, 120); gate.takeRequest(220)
        assertNull(gate.timedOutRequest)
        gate.takeRequest(720)
        assertEquals(XiaomiActivitySyncGate.Request.PAST, gate.timedOutRequest)
        gate.takeRequest(820)
        assertNull(gate.timedOutRequest)
    }
    @Test fun alternatePushFinishesBeforeWorkoutRequestsAndDoesNotRepeatPastList() {
        val gate = XiaomiActivitySyncGate(100, 500)
        gate.start(0)
        assertNull(gate.takeRequest(99))
        assertEquals(XiaomiActivitySyncGate.Request.TODAY, gate.takeRequest(100))
        gate.onList(1, 120) // Empty normal list does not mean no sleep on the watch.
        assertEquals(XiaomiActivitySyncGate.Request.PAST, gate.takeRequest(220))
        gate.onList(2, 240) // Two workout IDs arrive here.
        assertFalse(gate.canDownload(340))
        assertEquals(XiaomiActivitySyncGate.Request.ALTERNATE_TODAY, gate.takeRequest(340))
        gate.onTraffic(420); gate.onTraffic(490); gate.onTraffic(570) // Sleep files 7, 8, 9.
        assertFalse(gate.canDownload(669))
        assertTrue(gate.canDownload(670))
        gate.onList(1, 680) // Some firmware sends a late alternate-list response.
        assertNull(gate.takeRequest(780)) // Must not request PAST again.
        assertTrue(gate.canDownload(780))
    }
    @Test fun unsolicitedBurstDelaysNextListEvenAfterResponseArrives() {
        val gate = XiaomiActivitySyncGate(100, 500)
        gate.start(0); gate.takeRequest(100); gate.onList(1, 120)
        gate.onTraffic(180); gate.onTraffic(260)
        assertNull(gate.takeRequest(359))
        assertEquals(XiaomiActivitySyncGate.Request.PAST, gate.takeRequest(360))
    }
    @Test fun missingListResponseHasBoundedFallback() {
        val gate = XiaomiActivitySyncGate(100, 500)
        gate.start(0); gate.takeRequest(100)
        assertNull(gate.takeRequest(599))
        assertEquals(XiaomiActivitySyncGate.Request.PAST, gate.takeRequest(600))
        assertEquals(XiaomiActivitySyncGate.Request.ALTERNATE_TODAY, gate.takeRequest(1100))
        assertTrue(gate.canDownload(1200))
    }
    @Test fun wrongOrDuplicateListDoesNotAdvanceAnotherRequest() {
        val gate = XiaomiActivitySyncGate(100, 500)
        gate.start(0); gate.takeRequest(100); gate.onList(2, 120)
        assertNull(gate.takeRequest(220))
        gate.onList(1, 230)
        assertEquals(XiaomiActivitySyncGate.Request.PAST, gate.takeRequest(330))
        gate.onList(1, 340)
        assertNull(gate.takeRequest(440))
    }
    @Test fun connectionResetCancelsOutstandingListAndStartsFresh() {
        val gate = XiaomiActivitySyncGate(100, 500)
        gate.start(0); gate.takeRequest(100); gate.reset()
        assertFalse(gate.running); assertNull(gate.takeRequest(1000))
        gate.start(1000)
        assertEquals(XiaomiActivitySyncGate.Request.TODAY, gate.takeRequest(1100))
    }
}
