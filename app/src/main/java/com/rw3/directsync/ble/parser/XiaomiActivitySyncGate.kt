package com.rw3.directsync.ble.parser

/** Serializes list requests and the unsolicited file burst produced by RW3's alternate request. */
class XiaomiActivitySyncGate(private val quietMillis: Long = 3_000, private val listTimeoutMillis: Long = 15_000) {
    enum class Request(val subtype: Int) { TODAY(1), PAST(2), ALTERNATE_TODAY(1) }
    private var pending: Request? = null
    private var awaiting: Request? = null
    private var responseDeadline = 0L
    private var lastTraffic = Long.MIN_VALUE
    var timedOutRequest: Request? = null
        private set
    @Volatile var running = false
        private set

    @Synchronized fun start(now: Long) {
        reset()
        running = true
        pending = Request.TODAY
        lastTraffic = now
    }
    @Synchronized fun reset() {
        pending = null; awaiting = null; responseDeadline = 0; lastTraffic = Long.MIN_VALUE; running = false; timedOutRequest = null
    }
    @Synchronized fun onTraffic(now: Long) { lastTraffic = now }
    @Synchronized fun onList(subtype: Int, now: Long) {
        onTraffic(now)
        val expected = awaiting ?: return
        if (expected.subtype != subtype) return
        awaiting = null
        pending = after(expected)
    }
    private fun after(request: Request): Request? = when (request) {
        Request.TODAY -> Request.PAST
        Request.PAST -> Request.ALTERNATE_TODAY
        Request.ALTERNATE_TODAY -> null
    }
    @Synchronized fun delayMillis(now: Long): Long {
        val quiet = if (lastTraffic == Long.MIN_VALUE) 0 else (lastTraffic + quietMillis - now).coerceAtLeast(0)
        val response = if (awaiting == null) 0 else (responseDeadline - now).coerceAtLeast(0)
        return maxOf(quiet, response)
    }
    @Synchronized fun takeRequest(now: Long): Request? {
        timedOutRequest = null
        if (!running || delayMillis(now) > 0) return null
        awaiting?.let { timedOutRequest = it; pending = after(it); awaiting = null }
        val request = pending ?: return null
        pending = null
        lastTraffic = now
        // Alternate mode pushes files directly and may never return a list response.
        if (request != Request.ALTERNATE_TODAY) {
            awaiting = request
            responseDeadline = now + listTimeoutMillis
        }
        return request
    }
    @Synchronized fun canDownload(now: Long) = pending == null && awaiting == null && delayMillis(now) == 0L
    @Synchronized fun finish() { running = false }
}
