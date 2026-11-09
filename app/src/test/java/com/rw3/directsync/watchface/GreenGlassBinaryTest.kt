package com.rw3.directsync.watchface

import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

class GreenGlassBinaryTest {
    @Test fun rgb565RunsAndLiteralsRoundTrip() {
        val colors = IntArray(450) { if (it < 200) 0xFF00FF00.toInt() else (0xFF000000L or (it * 2654435761L and 0xFFFFFF)).toInt() }
        val decoded = XiaomiImageBlock.decode(XiaomiImageBlock.encodeRgb565(450, 1, colors))
        assertEquals(450, decoded.width)
        colors.indices.forEach { i ->
            val a = colors[i]; val b = decoded.argb[i]
            assertTrue(kotlin.math.abs((a ushr 16 and 255) - (b ushr 16 and 255)) <= 8)
            assertTrue(kotlin.math.abs((a ushr 8 and 255) - (b ushr 8 and 255)) <= 4)
            assertTrue(kotlin.math.abs((a and 255) - (b and 255)) <= 8)
        }
    }

    @Test fun malformedStreamsAreRejectedBeforeAllocationOrOverflow() {
        val valid = XiaomiImageBlock.encodeRgb565(2, 1, intArrayOf(-1, -1))
        for (bad in listOf(valid.copyOf(19), valid.copyOf().apply { this[20] = 0 },
            valid.copyOf().apply { this[20] = 127 }, valid.copyOf().apply { this[5] = 127 },
            valid.copyOf().apply { this[16] = 3 })) {
            assertThrows(IllegalArgumentException::class.java) { XiaomiImageBlock.decode(bad) }
        }
    }

    @Test fun profileRejectsOtherFacesAndPartialDownloads() {
        assertThrows(IllegalArgumentException::class.java) { GreenGlassBinary.validateOriginal(ByteArray(168)) }
        assertThrows(IllegalArgumentException::class.java) { GreenGlassBinary.validateOriginal(ByteArray(1926987)) }
    }

    @Test fun replacingImagesDoesNotRelocateAnyExistingWidgetOrOtherSlot() {
        val original = ByteArray(1024) { (it * 31).toByte() }
        val background = byteArrayOf(1, 2, 3)
        val preview = byteArrayOf(4, 5)
        val result = GreenGlassBinary.appendMainImages(original, background, preview)
        val changedWords = setOf(0x20, 0xAC, 0x1B0, 0x1B4)
        for (i in original.indices) if ((i and -4) !in changedWords) assertEquals("byte $i", original[i], result[i])
        val bb = ByteBuffer.wrap(result).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(1024, bb.getInt(0x1B0)); assertEquals(3, bb.getInt(0x1B4))
        assertEquals(1027, bb.getInt(0x20)); assertEquals(1027, bb.getInt(0xAC))
        assertArrayEquals(background + preview, result.copyOfRange(1024, result.size))
    }

    /** Local integration fixture: acquired from user's watch; deliberately excluded from Git. */
    @Test fun verifiedOriginalDecodesAllGlyphsAndPreservesResourcesAfterReplacement() {
        val file = File("build/qa/watchface-research/GreenGlass-265210144.bin")
        assumeTrue("Local original Green Glass fixture not present", file.exists())
        val original = file.readBytes()
        GreenGlassBinary.validateOriginal(original)
        val originalBackground = GreenGlassBinary.background(original)
        assertEquals(390, originalBackground.width); assertEquals(450, originalBackground.height)
        for (list in 0..3) for (digit in 0..9) {
            val sprite = GreenGlassBinary.digit(original, list, digit)
            assertEquals(if (list < 2) 22 else 64, sprite.width)
            assertTrue(sprite.argb.any { it ushr 24 > 0 })
        }
        val bg = XiaomiImageBlock.encodeRgb565(390, 450, IntArray(390 * 450) { 0xFF00FF00.toInt() })
        val thumb = XiaomiImageBlock.encodeRgb565(234, 270, IntArray(234 * 270) { -1 })
        val result = GreenGlassBinary.replaceMain(original, bg, thumb)
        val restored = result.copyOf(original.size)
        for (offset in listOf(0x20, 0xAC, 0x1B0, 0x1B4)) original.copyInto(restored, offset, offset, offset + 4)
        assertArrayEquals(original, restored)
        assertEquals(390, GreenGlassBinary.background(result).width)
    }
}
