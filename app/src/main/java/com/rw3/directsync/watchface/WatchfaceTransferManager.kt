package com.rw3.directsync.watchface

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.zip.CRC32
import kotlin.math.min

/**
 * State machine and chunked transfer manager for uploading watchface binaries to Redmi Watch 3:
 * - Coordinates Handshake:
 *   1. `WatchfaceInstallStart(id, size)` (Command type=4, subtype=4)
 *   2. Watch replies with `WatchfaceInstallStatus` (status=0 or 2)
 *   3. `DataUploadRequest(type=16, md5Sum, size)` (Command type=22, subtype=0)
 *   4. Watch replies with `DataUploadAck(md5Sum, errno=0, resumePosition, chunkSize)`
 * - Wraps watchface binary in Xiaomi MassData envelope:
 *   `0x00, 0x10, md5(16B), totalSize(4B LE), data(resumePos..end), crc32(4B LE)`
 * - Splits MassData envelope into `(chunkSize - 4)` byte parts, prepending
 *   `totalParts(2B LE), currentPart(2B LE)` (`1..totalParts`) and streaming over
 *   SPP V1 `CHANNEL_MASS = 5` (or SPP V2 `CHANNEL_DATA = 2`).
 * - Enforces chunk retry limits (max 3 retries).
 * - Automatically activates the newly installed watchface upon completion.
 */
class WatchfaceTransferManager(
    private val scope: CoroutineScope,
    private val sender: TransportSender,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val chunkDelayMs: Long = DEFAULT_CHUNK_DELAY_MS,
    private val verificationTimeoutMs: Long = 20_000L,
    private val onFinished: () -> Unit = {}
) {

    interface TransportSender {
        fun sendInstallStart(watchfaceId: String, sizeBytes: Int): Boolean
        fun sendDataUploadRequest(md5Bytes: ByteArray, sizeBytes: Int): Boolean
        fun sendDataChunk(chunk: ByteArray, channel: Int = 5): Boolean
        fun sendInstallFinish(watchfaceId: String): Boolean
        fun sendWatchfaceSet(watchfaceId: String): Boolean
        fun queryWatchfaces() {}
        fun appendLog(message: String)
    }

    companion object {
        private const val DEFAULT_CHUNK_SIZE = 4096
        const val DEFAULT_CHUNK_DELAY_MS = 25L // Flow control to prevent overflowing SPP serial buffers
        const val TYPE_WATCHFACE: Byte = 16 // 0x10
        private const val MAX_RETRIES = 3

        /**
         * Wraps raw watchface binary bytes in the 26-byte Xiaomi MassData envelope:
         * - Header (22 bytes LE): `[0x00, uploadType(0x10), md5(16B), fullSize(4B LE)]`
         * - Body: `data[resumePosition .. data.size]`
         * - Trailer (4 bytes LE): `CRC32(header + body)`
         */
        fun buildMassDataPayload(
            data: ByteArray,
            resumePosition: Int = 0,
            uploadType: Byte = TYPE_WATCHFACE
        ): ByteArray {
            val safeResume = resumePosition.coerceIn(0, data.size)
            val remainingLen = data.size - safeResume
            val md5 = MessageDigest.getInstance("MD5").digest(data)

            val buf1 = ByteBuffer.allocate(22 + remainingLen).order(ByteOrder.LITTLE_ENDIAN).apply {
                put(0.toByte())
                put(uploadType)
                put(md5)
                putInt(data.size)
                put(data, safeResume, remainingLen)
            }.array()

            val crc = CRC32().apply { update(buf1) }.value.toInt()
            return ByteBuffer.allocate(buf1.size + 4).order(ByteOrder.LITTLE_ENDIAN).apply {
                put(buf1)
                putInt(crc)
            }.array()
        }
    }

    private val _transferState = MutableStateFlow<WatchfaceTransferState>(WatchfaceTransferState.Idle)
    val transferState: StateFlow<WatchfaceTransferState> = _transferState.asStateFlow()

    private var currentJob: Job? = null
    private var pendingPackageData: ByteArray? = null
    private var currentWatchfaceId: String = ""
    private var activeChunkSize: Int = DEFAULT_CHUNK_SIZE
    @Volatile
    private var dataUploadRequested: Boolean = false

    fun isTransferActive(): Boolean {
        val st = _transferState.value
        return st is WatchfaceTransferState.PreparingUpload ||
                st is WatchfaceTransferState.Uploading ||
                st is WatchfaceTransferState.Verifying
    }

    /**
     * Initiates the watchface installation pipeline.
     */
    fun startUpload(watchfaceId: String, data: ByteArray) {
        if (isTransferActive()) {
            sender.appendLog("[Watchface] Unggahan sedang berjalan; permintaan baru diabaikan.")
            return
        }

        currentJob?.cancel()
        pendingPackageData = data
        currentWatchfaceId = watchfaceId
        activeChunkSize = DEFAULT_CHUNK_SIZE
        dataUploadRequested = false

        _transferState.value = WatchfaceTransferState.PreparingUpload(watchfaceId, data.size)
        sender.appendLog("[Watchface] Memulai pemasangan '$watchfaceId' (${data.size} byte)...")

        currentJob = scope.launch(ioDispatcher) {
            try {
                // Step 1: Send WatchfaceInstallStart (Command type=4, subtype=4)
                val startSent = sender.sendInstallStart(watchfaceId, data.size)
                if (!startSent) {
                    failTransfer("Gagal mengirim perintah awal instalasi ke jam tangan.")
                    return@launch
                }

                // Wait briefly for WatchfaceInstallStatus(status=0) from watch, then ensure DataUploadRequest is sent
                delay(250)
                if (!dataUploadRequested && _transferState.value is WatchfaceTransferState.PreparingUpload) {
                    if (!dispatchDataUploadRequest(data)) {
                        return@launch
                    }
                }

                // Fallback timeout in case watch does not reply with DataUploadAck within 10s
                delay(10_000)
                if (_transferState.value is WatchfaceTransferState.PreparingUpload) {
                    failTransfer("Jam tidak mengonfirmasi permohonan unggah. Coba hubungkan ulang.")
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failTransfer("Kesalahan unggah: ${e.message}")
            }
        }
    }

    @Synchronized
    private fun dispatchDataUploadRequest(data: ByteArray): Boolean {
        if (dataUploadRequested) return true
        dataUploadRequested = true
        val md5 = MessageDigest.getInstance("MD5").digest(data)
        val uploadReqSent = sender.sendDataUploadRequest(md5, data.size)
        if (!uploadReqSent) {
            failTransfer("Gagal mengirim permohonan unggah data ke jam tangan.")
            return false
        }
        sender.appendLog("[Watchface] Permohonan unggah dikirim. Menunggu konfirmasi jam...")
        return true
    }

    /**
     * Called when the watch responds with DataUploadAck (Command type=22, subtype=0).
     */
    fun onDataUploadAckReceived(resumePos: Int, chunkSize: Int, md5: ByteArray? = null) {
        val data = pendingPackageData ?: return
        if (md5 != null && !md5.contentEquals(MessageDigest.getInstance("MD5").digest(data))) return
        if (resumePos !in 0..data.size) {
            failTransfer("Posisi unggah dari jam tidak valid.")
            return
        }
        if (_transferState.value !is WatchfaceTransferState.PreparingUpload &&
            _transferState.value !is WatchfaceTransferState.Uploading
        ) return

        currentJob?.cancel()
        activeChunkSize = if (chunkSize in 512..16384) chunkSize else DEFAULT_CHUNK_SIZE
        sender.appendLog("[Watchface] Jam menyetujui unggah (chunkSize=$activeChunkSize, resumePos=$resumePos). Mengalirkan data...")

        currentJob = scope.launch(ioDispatcher) {
            try {
                executeChunkStream(data, resumePosition = resumePos, chunkSize = activeChunkSize)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failTransfer("Kesalahan alir chunk: ${e.message}")
            }
        }
    }

    private suspend fun executeChunkStream(data: ByteArray, resumePosition: Int, chunkSize: Int) {
        val totalBytes = data.size
        val massPayload = buildMassDataPayload(data, resumePosition, TYPE_WATCHFACE)
        val partSize = (chunkSize - 4).coerceAtLeast(128)
        val totalParts = (massPayload.size + partSize - 1) / partSize

        var partIndex = 0
        while (scope.isActive && partIndex < totalParts) {
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            val currentPart = partIndex + 1
            val startIndex = partIndex * partSize
            val endIndex = min(startIndex + partSize, massPayload.size)
            val sliceLen = endIndex - startIndex

            // 4-byte chunk header: [totalParts (uint16_le), currentPart (uint16_le)] + slice
            val chunkToSend = ByteArray(4 + sliceLen)
            chunkToSend[0] = (totalParts and 0xFF).toByte()
            chunkToSend[1] = ((totalParts ushr 8) and 0xFF).toByte()
            chunkToSend[2] = (currentPart and 0xFF).toByte()
            chunkToSend[3] = ((currentPart ushr 8) and 0xFF).toByte()
            System.arraycopy(massPayload, startIndex, chunkToSend, 4, sliceLen)

            var sent = false
            var attempts = 0
            while (!sent && attempts < MAX_RETRIES && scope.isActive) {
                sent = sender.sendDataChunk(chunkToSend, channel = 5)
                if (!sent) {
                    attempts++
                    sender.appendLog("[Watchface] Pengiriman part $currentPart/$totalParts gagal, mencoba ulang (#$attempts)...")
                    delay(50)
                }
            }

            if (!sent) {
                failTransfer("Koneksi gagal mengirimkan paket data pada bagian $currentPart/$totalParts.")
                return
            }

            partIndex++
            val approxBytesSent = min(totalBytes, ((currentPart.toLong() * totalBytes) / totalParts).toInt())
            val percent = ((currentPart * 100) / totalParts).coerceIn(0, 100)
            _transferState.value = WatchfaceTransferState.Uploading(
                watchfaceId = currentWatchfaceId,
                bytesSent = approxBytesSent,
                totalBytes = totalBytes,
                percent = percent
            )

            if (chunkDelayMs > 0) {
                delay(chunkDelayMs)
            }
        }

        if (partIndex >= totalParts) {
            sender.appendLog("[Watchface] Semua $totalParts bagian terkirim ($totalBytes byte). Mengaktifkan watchface...")
            _transferState.value = WatchfaceTransferState.Verifying(currentWatchfaceId)

            delay(400)
            if (!sender.sendWatchfaceSet(currentWatchfaceId)) {
                failTransfer("Gagal meminta aktivasi watchface.")
                return
            }
            var elapsed = 0L
            while (_transferState.value is WatchfaceTransferState.Verifying && elapsed < verificationTimeoutMs) {
                sender.queryWatchfaces()
                delay(500)
                elapsed += 500
            }
            if (_transferState.value is WatchfaceTransferState.Verifying)
                failTransfer("Data terkirim, tetapi jam belum mengonfirmasi watchface aktif. Periksa daftar watchface sebelum mencoba ulang.")
        }
    }

    /**
     * Called when the watch responds with WatchfaceInstallStatus (Command type=4, subtype=4).
     */
    fun onInstallStatusReceived(status: Int) {
        val currentState = _transferState.value
        if (currentState is WatchfaceTransferState.PreparingUpload) {
            val data = pendingPackageData ?: return
            if (status == 0 || status == 2) {
                sender.appendLog("[Watchface] Jam siap menerima paket watchface (status=$status).")
                dispatchDataUploadRequest(data)
            } else {
                failTransfer("Jam menolak instalasi paket (kode status: $status).")
            }
            return
        }

        if (currentState !is WatchfaceTransferState.Verifying &&
            currentState !is WatchfaceTransferState.Uploading
        ) return

        if (status == 0 || status == 2) {
            if (currentState is WatchfaceTransferState.Verifying) {
                sender.appendLog("[Watchface] Konfirmasi instalasi diterima dari jam (status=$status). Mengaktifkan watchface...")
                sender.sendWatchfaceSet(currentWatchfaceId)
                sender.queryWatchfaces()
            }
        } else {
            failTransfer("Jam menolak instalasi paket (kode status: $status).")
        }
    }

    fun cancelTransfer() {
        if (!isTransferActive()) return
        currentJob?.cancel()
        currentJob = null
        pendingPackageData = null
        _transferState.value = WatchfaceTransferState.Idle
        onFinished()
        sender.appendLog("[Watchface] Pemasangan dibatalkan oleh pengguna.")
    }

    private fun completeTransfer(message: String) {
        val id = currentWatchfaceId
        pendingPackageData = null
        _transferState.value = WatchfaceTransferState.Success(id, message)
        onFinished()
        sender.appendLog("[Watchface] Sukses: $message")
    }

    private fun failTransfer(errorMsg: String) {
        currentJob?.cancel()
        currentJob = null
        pendingPackageData = null
        _transferState.value = WatchfaceTransferState.Error(errorMsg)
        onFinished()
        sender.appendLog("[Watchface] Gagal: $errorMsg")
    }

    fun onWatchfaceActiveReported(id: String) {
        if (_transferState.value is WatchfaceTransferState.Verifying && id == currentWatchfaceId)
            completeTransfer("Watchface terpasang dan dikonfirmasi aktif oleh jam.")
    }

    fun onConnectionLost() {
        if (isTransferActive()) failTransfer("Koneksi jam terputus saat unggah. Hubungkan kembali sebelum mencoba ulang.")
    }
}
