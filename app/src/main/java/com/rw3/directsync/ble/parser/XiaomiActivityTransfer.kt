package com.rw3.directsync.ble.parser

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Ordered chunk assembly, including files pushed directly by the watch. */
class XiaomiActivityTransfer {
    private var fileId: ByteArray? = null
    private var total = 0
    private var last = 0
    private val buffer = ByteArrayOutputStream()
    val bytesBuffered get() = buffer.size()

    fun begin(id: ByteArray) { reset(); require(id.size == 7); fileId = id.copyOf() }
    fun reset() { fileId = null; total = 0; last = 0; buffer.reset() }

    fun accept(chunk: ByteArray): ByteArray? {
        require(chunk.size >= 4) { "Header chunk terpotong" }
        val header = ByteBuffer.wrap(chunk).order(ByteOrder.LITTLE_ENDIAN)
        val count = header.short.toInt() and 65535
        val number = header.short.toInt() and 65535
        require(count in 1..4096 && number in 1..count) { "Nomor chunk tidak valid: $number/$count" }
        if (number == last && count == total) return null
        require(number == last + 1 && (total == 0 || count == total)) { "Urutan chunk terputus" }
        require(buffer.size() + chunk.size - 4 <= 4 * 1024 * 1024) { "File aktivitas terlalu besar" }
        total = count; last = number
        buffer.write(chunk, 4, chunk.size - 4)
        if (number < count) return null
        val result = buffer.toByteArray()
        reset()
        // The response carries its own file identity; the watch may finish an earlier queued
        // download. Its type, CRC and timestamps are validated by the file parser before saving.
        require(result.size >= 13) { "File aktivitas terpotong" }
        return result
    }
}
