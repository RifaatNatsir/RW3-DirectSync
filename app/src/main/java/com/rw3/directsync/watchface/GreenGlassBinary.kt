package com.rw3.directsync.watchface

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

/** Profile for the exact, verified RW3 Green Glass release. Other versions fail closed. */
object GreenGlassBinary {
    const val ID = "265210144"
    const val ORIGINAL_SHA256 = "08274c34a003c705d1f03006801bb386b70edc0fa0046cc88517d268f70ea2bb"
    private const val BACKGROUND_ENTRY = 0x1A8
    private const val MAIN_PREVIEW = 0x318

    fun validateOriginal(data: ByteArray) {
        require(data.size == 1926987 && digest(data) == ORIGINAL_SHA256) {
            "Pilih Green Glass asli ID 265210144 (versi 1.926.987 byte). Paket ini bukan versi template yang didukung."
        }
    }

    fun digest(data: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(data)
        .joinToString("") { "%02x".format(it) }

    fun background(original: ByteArray): XiaomiImageBlock.Image {
        val bb = ByteBuffer.wrap(original).order(ByteOrder.LITTLE_ENDIAN)
        val offset = bb.getInt(BACKGROUND_ENTRY + 8)
        val size = bb.getInt(BACKGROUND_ENTRY + 12)
        return XiaomiImageBlock.decode(original.copyOfRange(offset, offset + size))
    }

    /** Returns a digit sprite from the original image list; widgets use these exact assets. */
    fun digit(original: ByteArray, listIndex: Int, digit: Int): XiaomiImageBlock.Image {
        require(listIndex in 0..3 && digit in 0..9)
        val bb = ByteBuffer.wrap(original).order(ByteOrder.LITTLE_ENDIAN)
        val entry = 0x1B8 + 16 * listIndex
        val offset = bb.getInt(entry + 8)
        val w = bb.getShort(offset + 4).toInt() and 65535
        val h = bb.getShort(offset + 6).toInt() and 65535
        var frame = offset + 52
        repeat(digit) { frame += bb.getInt(offset + 12 + it * 4) }
        val size = bb.getInt(offset + 12 + digit * 4)
        val encoded = ByteBuffer.allocate(12 + size).order(ByteOrder.LITTLE_ENDIAN).apply {
            put(0); put(4); putShort(0); putShort(w.toShort()); putShort(h.toShort()); putInt(size)
            put(original, frame, size)
        }.array()
        return XiaomiImageBlock.decode(encoded)
    }

    /**
     * Append replacements; keep every existing resource/widget byte at its original address.
     * Only the main background and its two preview references are redirected. Slot 2 is untouched.
     */
    fun replaceMain(original: ByteArray, background: ByteArray, preview: ByteArray): ByteArray {
        validateOriginal(original)
        val bg = XiaomiImageBlock.decode(background)
        val thumb = XiaomiImageBlock.decode(preview)
        require(bg.width == 390 && bg.height == 450 && thumb.width == 234 && thumb.height == 270)
        return appendMainImages(original, background, preview)
    }

    internal fun appendMainImages(original: ByteArray, background: ByteArray, preview: ByteArray): ByteArray {
        require(original.size >= BACKGROUND_ENTRY + 16)
        val result = original + background + preview
        val bb = ByteBuffer.wrap(result).order(ByteOrder.LITTLE_ENDIAN)
        bb.putInt(BACKGROUND_ENTRY + 8, original.size)
        bb.putInt(BACKGROUND_ENTRY + 12, background.size)
        bb.putInt(0x20, original.size + background.size)
        bb.putInt(0xAC, original.size + background.size)
        // Name and ID stay original. All glyphs, widget references and the second slot stay intact.
        return result
    }
}
