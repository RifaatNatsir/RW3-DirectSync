package com.rw3.directsync.watchface

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Bounded Xiaomi LVGL V2 codec. No Android dependencies; lengths are checked before allocation. */
object XiaomiImageBlock {
    data class Image(val width: Int, val height: Int, val argb: IntArray, val hasAlpha: Boolean = true)

    fun decode(block: ByteArray): Image {
        require(block.size >= 20) { "Blok gambar terpotong." }
        val bb = ByteBuffer.wrap(block).order(ByteOrder.LITTLE_ENDIAN)
        val w = bb.getShort(4).toInt() and 65535
        val h = bb.getShort(6).toInt() and 65535
        require(w in 1..450 && h in 1..450) { "Dimensi gambar tidak didukung." }
        require(bb.getInt(8) == block.size - 12 && bb.getInt(12) == 0x5AA521E0) { "Header gambar tidak valid." }
        val format = bb.getInt(16)
        val unit = when (format and 15) { 2 -> 2; 4 -> 4; else -> throw IllegalArgumentException("Format piksel tidak didukung.") }
        require(format ushr 4 == w * h * unit) { "Ukuran piksel tidak cocok." }
        val pixels = IntArray(w * h)
        var pos = 20
        var target = 0
        fun pixel(): Int {
            require(pos + unit <= block.size) { "Data piksel terpotong." }
            if (unit == 4) return bb.getInt(pos).also { pos += 4 }
            val v = bb.getShort(pos).toInt() and 65535
            pos += 2
            return (255 shl 24) or ((v ushr 11) * 255 / 31 shl 16) or
                (((v ushr 5) and 63) * 255 / 63 shl 8) or ((v and 31) * 255 / 31)
        }
        while (target < pixels.size) {
            require(pos < block.size) { "Stream RLE terpotong." }
            val control = block[pos++].toInt() and 255
            val count = control and 127
            require(count > 0 && count <= pixels.size - target) { "Panjang RLE tidak valid." }
            if (control and 128 != 0) repeat(count) { pixels[target++] = pixel() }
            else {
                val color = pixel()
                repeat(count) { pixels[target++] = color }
            }
        }
        require(pos == block.size) { "Sisa data gambar tidak dikenal." }
        return Image(w, h, pixels, hasAlpha = unit == 4)
    }

    fun encodeRgb565(w: Int, h: Int, argb: IntArray): ByteArray {
        require(w in 1..450 && h in 1..450 && argb.size == w * h)
        val pixels = IntArray(argb.size) { i ->
            val c = argb[i]
            ((c ushr 19 and 31) shl 11) or ((c ushr 10 and 63) shl 5) or (c ushr 3 and 31)
        }
        val stream = ByteArrayOutputStream()
        fun writePixel(p: Int) { stream.write(p and 255); stream.write(p ushr 8) }
        var i = 0
        while (i < pixels.size) {
            var run = 1
            while (i + run < pixels.size && run < 127 && pixels[i + run] == pixels[i]) run++
            if (run >= 2) { stream.write(run); writePixel(pixels[i]); i += run }
            else {
                val start = i++
                while (i < pixels.size && i - start < 127 &&
                    !(i + 1 < pixels.size && pixels[i] == pixels[i + 1])) i++
                stream.write(128 or (i - start))
                for (k in start until i) writePixel(pixels[k])
            }
        }
        val payload = stream.toByteArray()
        return ByteBuffer.allocate(20 + payload.size).order(ByteOrder.LITTLE_ENDIAN).apply {
            put(3); put(4); putShort(0); putShort(w.toShort()); putShort(h.toShort())
            putInt(8 + payload.size); putInt(0x5AA521E0); putInt((w * h * 2 shl 4) or 2); put(payload)
        }.array()
    }

    fun encodeArgb8888(image: Image): ByteArray {
        require(image.width in 1..450 && image.height in 1..450 && image.argb.size == image.width * image.height)
        val stream = ByteArrayOutputStream()
        fun pixel(c: Int) { repeat(4) { stream.write(c ushr (it * 8) and 255) } }
        val pixels = image.argb
        var i = 0
        while (i < pixels.size) {
            var run = 1
            while (i + run < pixels.size && run < 127 && pixels[i + run] == pixels[i]) run++
            if (run >= 2) { stream.write(run); pixel(pixels[i]); i += run }
            else {
                val start = i++
                while (i < pixels.size && i - start < 127 && !(i + 1 < pixels.size && pixels[i] == pixels[i + 1])) i++
                stream.write(128 or (i - start))
                for (n in start until i) pixel(pixels[n])
            }
        }
        val payload = stream.toByteArray()
        return ByteBuffer.allocate(20 + payload.size).order(ByteOrder.LITTLE_ENDIAN).apply {
            put(0); put(4); putShort(0); putShort(image.width.toShort()); putShort(image.height.toShort())
            putInt(8 + payload.size); putInt(0x5AA521E0); putInt((pixels.size * 4 shl 4) or 4); put(payload)
        }.array()
    }

    fun encodeImageList(images: List<Image>): ByteArray {
        require(images.size in 1..60)
        val first = images.first()
        require(images.all { it.width == first.width && it.height == first.height })
        val payloads = images.map { image ->
            val block = encodeArgb8888(image)
            block.copyOfRange(12, block.size)
        }
        return ByteBuffer.allocate(12 + 4 * images.size + payloads.sumOf { it.size }).order(ByteOrder.LITTLE_ENDIAN).apply {
            put(0); put(images.size.toByte()); put(4); put(0); putShort(first.width.toShort()); putShort(first.height.toShort())
            putInt(payloads.sumOf { it.size }); payloads.forEach { putInt(it.size) }; payloads.forEach { put(it) }
        }.array()
    }

    fun decodeImageList(block: ByteArray): List<Image> {
        require(block.size >= 16)
        val bb = ByteBuffer.wrap(block).order(ByteOrder.LITTLE_ENDIAN)
        val count = block[1].toInt() and 255
        require(count in 1..60 && block.size >= 12 + count * 4)
        require(bb.getInt(8) == block.size - 12 - count * 4)
        var at = 12 + count * 4
        val result = List(count) { n ->
            val size = bb.getInt(12 + n * 4)
            require(size >= 8 && size <= block.size - at)
            val image = ByteBuffer.allocate(12 + size).order(ByteOrder.LITTLE_ENDIAN).apply {
                put(0); put(4); putShort(0); putShort(bb.getShort(4)); putShort(bb.getShort(6)); putInt(size)
                put(block, at, size)
            }.array()
            at += size
            decode(image)
        }
        require(at == block.size)
        return result
    }
}
