package com.rw3.directsync.ble.parser

import org.junit.Assert.*
import org.junit.Test

class XiaomiActivityRequestQueueTest {
    private fun id(n: Int) = byteArrayOf(n.toByte(), 0, 0, 0, 32, 7, 0xa1.toByte())
    @Test fun pushedHistoryDoesNotSkipEitherWorkout() {
        val q = XiaomiActivityRequestQueue()
        q.offer(id(1)); q.offer(id(2))
        assertArrayEquals(id(1), q.next())
        q.complete(id(3)) // An unsolicited HR/sleep file arrives before workout 1.
        assertArrayEquals(id(1), q.active)
        assertNull(q.next())
        q.complete(id(1))
        assertArrayEquals(id(2), q.next())
        q.complete(id(2)); assertNull(q.active); assertNull(q.next())
    }
    @Test fun duplicateListsAndPushedQueuedFilesDoNotDuplicateDownloads() {
        val q = XiaomiActivityRequestQueue()
        assertTrue(q.offer(id(1))); assertFalse(q.offer(id(1)))
        q.offer(id(2)); q.complete(id(2)); q.next(); q.complete(id(1))
        assertNull(q.next()); assertFalse(q.offer(id(2)))
    }
    @Test fun timeoutRetriesSameFileBeforeContinuing() {
        val q = XiaomiActivityRequestQueue()
        q.offer(id(1)); q.offer(id(2)); q.next()
        assertArrayEquals(id(1), q.retry()); assertArrayEquals(id(1), q.retry())
        assertNull(q.retry()); assertArrayEquals(id(2), q.next())
    }
    @Test fun newConnectionCanFetchAllFilesAgain() {
        val q = XiaomiActivityRequestQueue()
        q.offer(id(1)); q.next(); q.reset()
        assertNull(q.active); assertTrue(q.offer(id(1))); assertArrayEquals(id(1), q.next())
    }

    @Test fun partialSleepPushIsRetriedBeforePendingWorkoutsWithoutSkippingActiveRequest() {
        val q = XiaomiActivityRequestQueue()
        q.offer(id(1)); q.offer(id(2)); q.next()
        q.offer(id(3), priority = true)
        assertArrayEquals(id(1), q.active)
        q.complete(id(1))
        assertArrayEquals(id(3), q.next())
        q.complete(id(3))
        assertArrayEquals(id(2), q.next())
    }
}
