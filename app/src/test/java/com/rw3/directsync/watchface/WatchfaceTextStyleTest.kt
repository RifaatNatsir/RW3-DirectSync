package com.rw3.directsync.watchface

import org.junit.Assert.*
import org.junit.Test

class WatchfaceTextStyleTest {
    @Test fun fairyFlowerOpaqueBlackMaskBecomesCoverageBeforeTinting() {
        val source = XiaomiImageBlock.Image(3, 1, intArrayOf(0xFF000000.toInt(), 0xFF808080.toInt(), -1))
        val normalized = PhotoWatchfaceProfile.FAIRY_FLOWER.glyphForStyling(source)
        val result = WatchfaceGlyphStyler.apply(normalized, WatchfaceTextStyle(color = 0xFF000000.toInt()))
        assertArrayEquals(intArrayOf(0, 0x80000000.toInt(), 0xFF000000.toInt()), result.argb)
        assertSame(source, PhotoWatchfaceProfile.GREEN_GLASS.glyphForStyling(source))
    }
    @Test fun tintPreservesCoverageAndSupportsBlack() {
        val source = XiaomiImageBlock.Image(3, 1, intArrayOf(0, 0x80FFFFFF.toInt(), -1))
        val result = WatchfaceGlyphStyler.apply(source, WatchfaceTextStyle(color = 0xFF000000.toInt()))
        assertArrayEquals(intArrayOf(0, 0x80000000.toInt(), 0xFF000000.toInt()), result.argb)
    }
    @Test fun gaussianShadowCreatesSoftHaloWithoutChangingMetrics() {
        val pixels = IntArray(81).apply { this[40] = -1 }
        val result = WatchfaceGlyphStyler.apply(XiaomiImageBlock.Image(9, 9, pixels), WatchfaceTextStyle(0xFFFFFF00.toInt(), true, 3))
        assertEquals(9, result.width); assertEquals(9, result.height)
        assertEquals(0xFFFFFF00.toInt(), result.argb[40])
        val near = result.argb[41] ushr 24
        val far = result.argb[43] ushr 24
        assertTrue(near > far && far > 0)
        assertEquals(0, result.argb[41] and 0xFFFFFF)
        assertEquals(0, result.argb[0])
    }
    @Test fun originalRgbaBlackIsNotTreatedAsTransparentBackground() {
        val rgba = XiaomiImageBlock.Image(1, 1, intArrayOf(0xFF000000.toInt()), true)
        val rgb = rgba.copy(hasAlpha = false)
        assertEquals(0xFF000000.toInt(), WatchfaceGlyphStyler.apply(rgba, WatchfaceTextStyle()).argb[0])
        assertEquals(0, WatchfaceGlyphStyler.apply(rgb, WatchfaceTextStyle()).argb[0])
    }
    @Test fun rgbaRleRoundTripsAlphaAndLongRunsExactly() {
        val pixels = IntArray(450) { if (it < 200) 0x40000000 else ((it * 379).toLong() * 2654435761L).toInt() }
        val image = XiaomiImageBlock.Image(450, 1, pixels)
        val decoded = XiaomiImageBlock.decode(XiaomiImageBlock.encodeArgb8888(image))
        assertTrue(decoded.hasAlpha)
        assertArrayEquals(pixels, decoded.argb)
    }
    @Test fun imageListKeepsFrameCountOrderSizeAndAlpha() {
        val a = XiaomiImageBlock.Image(2, 1, intArrayOf(0x80FFFF00.toInt(), 0x40000000))
        val b = a.copy(argb = intArrayOf(-1, 0))
        val decoded = XiaomiImageBlock.decodeImageList(XiaomiImageBlock.encodeImageList(listOf(a, b)))
        assertEquals(2, decoded.size)
        assertArrayEquals(a.argb, decoded[0].argb)
        assertArrayEquals(b.argb, decoded[1].argb)
        assertEquals(2, decoded[0].width)
    }
    @Test fun invalidStyleAndBrokenImageListAreRejected() {
        assertThrows(IllegalArgumentException::class.java) { WatchfaceTextStyle(feather = 7) }
        assertThrows(IllegalArgumentException::class.java) { WatchfaceTextStyle(color = 0x80FFFFFF.toInt()) }
        assertThrows(IllegalArgumentException::class.java) { XiaomiImageBlock.decodeImageList(ByteArray(16)) }
    }
}
