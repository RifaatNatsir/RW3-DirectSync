package com.rw3.directsync.ble.protocol

/** RFCOMM is a byte stream: even the preamble can be split across reads. */
object XiaomiSppFrameParser {
    fun consume(buffer: ByteArray, v1: (Int, Int, ByteArray) -> Unit, v2: (Int, Int, ByteArray) -> Unit): ByteArray {
        var offset = 0
        fun u8(at: Int) = buffer[at].toInt() and 255
        fun u16(at: Int) = u8(at) or (u8(at + 1) shl 8)
        while (offset < buffer.size) {
            val remaining = buffer.size - offset
            // Keep a possible incomplete prefix instead of dropping BA / BA DC / A5.
            if (remaining == 1 && u8(offset) in listOf(0xBA, 0xA5)) break
            if (remaining == 2 && u8(offset) == 0xBA && u8(offset + 1) == 0xDC) break
            if (remaining >= 3 && u8(offset) == 0xBA && u8(offset + 1) == 0xDC && u8(offset + 2) == 0xFE) {
                if (remaining < 11) break
                val length = u16(offset + 5) - 3
                if (length < 0) { offset++; continue }
                if (remaining < length + 11) break
                if (u8(offset + length + 10) == 0xEF) {
                    v1(u8(offset + 3) and 15, u8(offset + 9), buffer.copyOfRange(offset + 10, offset + 10 + length))
                }
                offset += length + 11
                continue
            }
            if (remaining >= 2 && u8(offset) == 0xA5 && u8(offset + 1) == 0xA5) {
                if (remaining < 8) break
                val length = u16(offset + 4)
                if (remaining < length + 8) break
                v2(u8(offset + 2) and 15, u8(offset + 3), buffer.copyOfRange(offset + 8, offset + 8 + length))
                offset += length + 8
                continue
            }
            offset++
        }
        return buffer.copyOfRange(offset, buffer.size)
    }
}
