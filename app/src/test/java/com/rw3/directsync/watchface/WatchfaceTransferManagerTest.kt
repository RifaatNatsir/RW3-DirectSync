package com.rw3.directsync.watchface

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class WatchfaceTransferManagerTest {

    private class MockTransportSender : WatchfaceTransferManager.TransportSender {
        val calls = mutableListOf<String>()
        val sentChunks = mutableListOf<ByteArray>()
        var chunkFailCount = 0
        var failChunkCompletely = false

        override fun sendInstallStart(watchfaceId: String, sizeBytes: Int): Boolean {
            synchronized(calls) {
                calls.add("InstallStart($watchfaceId, $sizeBytes)")
            }
            return true
        }

        override fun sendDataUploadRequest(md5Bytes: ByteArray, sizeBytes: Int): Boolean {
            synchronized(calls) {
                calls.add("DataUploadRequest($sizeBytes)")
            }
            return true
        }

        override fun sendDataChunk(chunk: ByteArray, channel: Int): Boolean {
            if (failChunkCompletely) return false
            if (chunkFailCount > 0) {
                chunkFailCount--
                return false
            }
            synchronized(sentChunks) {
                sentChunks.add(chunk)
            }
            synchronized(calls) {
                calls.add("DataChunk(${chunk.size})")
            }
            return true
        }

        override fun sendInstallFinish(watchfaceId: String): Boolean {
            synchronized(calls) {
                calls.add("InstallFinish($watchfaceId)")
            }
            return true
        }

        override fun sendWatchfaceSet(watchfaceId: String): Boolean {
            synchronized(calls) {
                calls.add("WatchfaceSet($watchfaceId)")
            }
            return true
        }

        override fun appendLog(message: String) {
            println("[TEST LOG] $message")
        }
    }

    private suspend fun waitUntil(timeoutMs: Long = 2000L, condition: () -> Boolean): Boolean {
        var elapsed = 0L
        while (!condition() && elapsed < timeoutMs) {
            delay(50)
            elapsed += 50
        }
        return condition()
    }

    @Test
    fun startUpload_executesHandshakeAndMassDataChunks() = runBlocking {
        val scope = CoroutineScope(Dispatchers.Default)
        val sender = MockTransportSender()
        val manager = WatchfaceTransferManager(
            scope = scope,
            sender = sender,
            ioDispatcher = Dispatchers.Default,
            chunkDelayMs = 0L
        )

        // 10 KB payload -> MassData envelope adds 26 bytes = 10266 bytes
        // With chunkSize = 4096, partSize = 4092 -> 3 parts: 4092 + 4092 + 2082
        // Each chunk adds 4-byte [totalParts, currentPart] header -> 4096, 4096, 2086 bytes
        val payload = ByteArray(10 * 1024) { (it % 256).toByte() }

        manager.startUpload("265246003", payload)

        assertTrue(waitUntil { sender.calls.contains("InstallStart(265246003, 10240)") })
        // Simulate watch replying with installStatus = 0
        manager.onInstallStatusReceived(0)
        assertTrue(waitUntil { sender.calls.contains("DataUploadRequest(10240)") })

        // Simulate Watch acknowledging upload with 4096B chunks
        manager.onDataUploadAckReceived(resumePos = 0, chunkSize = 4096)

        // Wait until 3 chunks are sent
        assertTrue(waitUntil { sender.sentChunks.size == 3 })

        assertEquals(4096, sender.sentChunks[0].size)
        assertEquals(4096, sender.sentChunks[1].size)
        assertEquals(2086, sender.sentChunks[2].size)

        // Verify 4-byte header on chunk 0: totalParts=3, currentPart=1
        val hdr0 = ByteBuffer.wrap(sender.sentChunks[0], 0, 4).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(3, hdr0.short.toInt() and 0xFFFF)
        assertEquals(1, hdr0.short.toInt() and 0xFFFF)

        // Verify watchface activation and success state
        assertTrue(waitUntil { sender.calls.contains("WatchfaceSet(265246003)") })
        assertTrue(waitUntil { manager.transferState.value is WatchfaceTransferState.Success })
        val state = manager.transferState.value
        assertTrue(state is WatchfaceTransferState.Success)
        assertEquals("265246003", (state as WatchfaceTransferState.Success).watchfaceId)
    }

    @Test
    fun startUpload_watchRejectsInstallation_reportsError() = runBlocking {
        val scope = CoroutineScope(Dispatchers.Default)
        val sender = MockTransportSender()
        val manager = WatchfaceTransferManager(
            scope = scope,
            sender = sender,
            ioDispatcher = Dispatchers.Default,
            chunkDelayMs = 0L
        )

        val payload = ByteArray(4096) { 0x33 }
        manager.startUpload("wf_bad", payload)

        assertTrue(waitUntil { sender.calls.contains("InstallStart(wf_bad, 4096)") })

        // Watch rejects with status 1 (error / invalid)
        manager.onInstallStatusReceived(1)

        assertTrue(waitUntil { manager.transferState.value is WatchfaceTransferState.Error })
        val state = manager.transferState.value
        assertTrue((state as WatchfaceTransferState.Error).message.contains("menolak"))
    }

    @Test
    fun cancelTransfer_stopsOperationAndReturnsToIdle() = runBlocking {
        val scope = CoroutineScope(Dispatchers.Default)
        val sender = MockTransportSender()
        val manager = WatchfaceTransferManager(
            scope = scope,
            sender = sender,
            ioDispatcher = Dispatchers.Default,
            chunkDelayMs = 100L
        )

        val payload = ByteArray(8192) { 0x44 }
        manager.startUpload("wf_cancel", payload)

        assertTrue(waitUntil { manager.isTransferActive() })

        manager.cancelTransfer()
        assertTrue(waitUntil { !manager.isTransferActive() })

        assertEquals(WatchfaceTransferState.Idle, manager.transferState.value)
    }

    @Test
    fun retryLimitExceeded_reportsError() = runBlocking {
        val scope = CoroutineScope(Dispatchers.Default)
        val sender = MockTransportSender().apply {
            failChunkCompletely = true
        }
        val manager = WatchfaceTransferManager(
            scope = scope,
            sender = sender,
            ioDispatcher = Dispatchers.Default,
            chunkDelayMs = 0L
        )

        val payload = ByteArray(4096) { 0x55 }
        manager.startUpload("wf_fail", payload)

        assertTrue(waitUntil { sender.calls.contains("InstallStart(wf_fail, 4096)") })

        manager.onDataUploadAckReceived(resumePos = 0, chunkSize = 4096)

        assertTrue(waitUntil(timeoutMs = 3000) { manager.transferState.value is WatchfaceTransferState.Error })
        val state = manager.transferState.value
        assertTrue((state as WatchfaceTransferState.Error).message.contains("Koneksi gagal"))
    }

    @Test
    fun encodeLvglRle16Block_validatesHeaderAndRoundtripSize() {
        val w = 390
        val h = 450
        val pixels = IntArray(w * h) { idx -> if (idx % 2 == 0) 0x6FE0 else 0x0D12 }
        val block = WallpaperBinPackager.encodeLvglRle16Block(pixels, w, h)

        val bb = ByteBuffer.wrap(block).order(ByteOrder.LITTLE_ENDIAN)
        val bitmapType = bb.get().toInt() and 0xFF
        val compressionType = bb.get().toInt() and 0xFF
        val reserved = bb.short.toInt() and 0xFFFF
        val width = bb.short.toInt() and 0xFFFF
        val height = bb.short.toInt() and 0xFFFF
        val rlePayloadSize = bb.int

        assertEquals(3, bitmapType) // RGB565
        assertEquals(4, compressionType) // LVGL RLE V2
        assertEquals(0, reserved)
        assertEquals(w, width)
        assertEquals(h, height)
        assertEquals(12 + rlePayloadSize, block.size)
    }
}
