package com.rw3.directsync.watchface

import android.content.Context
import android.graphics.Bitmap
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Builds a genuine Redmi Watch 3 (`lchz.watch.m65sa`) binary watchface package (`.bin`)
 * by encoding the user's cropped 390×450 wallpaper and 234×270 preview thumbnail into
 * Xiaomi LVGL RLE16 V2 (`compressionType = 4`, RGB565 LE) and patching the clean template asset.
 */
object WallpaperBinPackager {

    const val TEMPLATE_ASSET_NAME = "rw3_wallpaper_template.bin"
    const val DEFAULT_WALLPAPER_WATCHFACE_ID = "265246003"
    const val DEFAULT_WALLPAPER_NAME = "Custom Photo"

    const val BG_WIDTH = 390
    const val BG_HEIGHT = 450
    const val BG_CORNER_RADIUS = 86

    const val THUMB_WIDTH = 234
    const val THUMB_HEIGHT = 270
    const val THUMB_CORNER_RADIUS = 51

    private const val HEADER_SIZE = 0x07F0
    private const val RESOURCE_TABLE_OFFSET = 0x01B0
    private const val RESOURCE_ENTRY_COUNT = 100

    /**
     * Packages a 390×450 [wallpaperBitmap] into a complete Redmi Watch 3 `.bin` watchface binary
     * using the bundled [TEMPLATE_ASSET_NAME].
     */
    fun buildFromAssetTemplate(
        context: Context,
        wallpaperBitmap: Bitmap,
        thumbnailSourceBitmap: Bitmap? = null,
        watchfaceId: String = DEFAULT_WALLPAPER_WATCHFACE_ID,
        watchfaceName: String = DEFAULT_WALLPAPER_NAME
    ): ByteArray {
        val templateBytes = context.assets.open(TEMPLATE_ASSET_NAME).use { it.readBytes() }
        return buildFromTemplateBytes(
            templateBytes = templateBytes,
            wallpaperBitmap = wallpaperBitmap,
            thumbnailSourceBitmap = thumbnailSourceBitmap,
            watchfaceId = watchfaceId,
            watchfaceName = watchfaceName
        )
    }

    /**
     * Pure function that patches [templateBytes] with RLE16-compressed RGB565 blocks of [wallpaperBitmap]
     * and [thumbnailSourceBitmap].
     */
    fun buildFromTemplateBytes(
        templateBytes: ByteArray,
        wallpaperBitmap: Bitmap,
        thumbnailSourceBitmap: Bitmap? = null,
        watchfaceId: String = DEFAULT_WALLPAPER_WATCHFACE_ID,
        watchfaceName: String = DEFAULT_WALLPAPER_NAME
    ): ByteArray {
        val scaledBg = if (wallpaperBitmap.width == BG_WIDTH && wallpaperBitmap.height == BG_HEIGHT) {
            wallpaperBitmap
        } else {
            Bitmap.createScaledBitmap(wallpaperBitmap, BG_WIDTH, BG_HEIGHT, true)
        }

        val thumbSource = thumbnailSourceBitmap ?: scaledBg
        val scaledThumb = if (thumbSource.width == THUMB_WIDTH && thumbSource.height == THUMB_HEIGHT) {
            thumbSource
        } else {
            Bitmap.createScaledBitmap(thumbSource, THUMB_WIDTH, THUMB_HEIGHT, true)
        }

        val bgRgb565 = bitmapToRoundedRgb565Array(scaledBg, BG_WIDTH, BG_HEIGHT, BG_CORNER_RADIUS)
        val thumbRgb565 = bitmapToRoundedRgb565Array(scaledThumb, THUMB_WIDTH, THUMB_HEIGHT, THUMB_CORNER_RADIUS)

        val bgBlock = encodeLvglRle16Block(bgRgb565, BG_WIDTH, BG_HEIGHT)
        val thumbBlock = encodeLvglRle16Block(thumbRgb565, THUMB_WIDTH, THUMB_HEIGHT)

        return patchTemplateBinary(
            templateBytes = templateBytes,
            newThumbBlock = thumbBlock,
            newBgBlock = bgBlock,
            watchfaceId = watchfaceId,
            watchfaceName = watchfaceName
        )
    }

    /**
     * Converts a [Bitmap] into an array of 16-bit RGB565 pixels (0x0000..0xFFFF), masking
     * pixels outside the watch's rounded corners ([cornerRadius]) to black (`0x0000`).
     */
    fun bitmapToRoundedRgb565Array(
        bitmap: Bitmap,
        width: Int,
        height: Int,
        cornerRadius: Int
    ): IntArray {
        val argb = IntArray(width * height)
        bitmap.getPixels(argb, 0, width, 0, 0, width, height)

        val out = IntArray(width * height)
        val rSq = cornerRadius * cornerRadius

        for (y in 0 until height) {
            val rowOffset = y * width
            val inTop = y < cornerRadius
            val inBottom = y > height - 1 - cornerRadius
            val cy = when {
                inTop -> cornerRadius
                inBottom -> height - 1 - cornerRadius
                else -> y
            }
            val dy = y - cy
            val dySq = dy * dy

            for (x in 0 until width) {
                if (inTop || inBottom) {
                    val inLeft = x < cornerRadius
                    val inRight = x > width - 1 - cornerRadius
                    if (inLeft || inRight) {
                        val cx = if (inLeft) cornerRadius else (width - 1 - cornerRadius)
                        val dx = x - cx
                        if (dx * dx + dySq > rSq) {
                            out[rowOffset + x] = 0
                            continue
                        }
                    }
                }

                val c = argb[rowOffset + x]
                val r5 = (c ushr 19) and 0x1F
                val g6 = (c ushr 10) and 0x3F
                val b5 = (c ushr 3) and 0x1F
                out[rowOffset + x] = (r5 shl 11) or (g6 shl 5) or b5
            }
        }
        return out
    }

    /**
     * Encodes an array of RGB565 pixels into a Xiaomi watchface image block:
     * - 12-byte Block Header: `[03, 04, 00, 00, width(u16_le), height(u16_le), rlePayloadSize(u32_le)]`
     * - 8-byte LVGL RLE16 Sub-Header: `[E0, 21, A5, 5A, ((uncompressedBytes << 4) | 2)(u32_le)]`
     * - RLE16 V2 packet stream:
     *   - Repeat run (`(ctrl & 0x80) == 0`): `ctrl = count (1..127)`, followed by 1 pixel (`uint16_le`)
     *   - Literal run (`(ctrl & 0x80) != 0`): `ctrl = 0x80 | count (1..127)`, followed by `count` pixels (`uint16_le`)
     */
    fun encodeLvglRle16Block(pixelsRgb565: IntArray, width: Int, height: Int): ByteArray {
        require(pixelsRgb565.size == width * height) {
            "Pixel count (${pixelsRgb565.size}) does not match ${width}x${height}"
        }

        val uncompressedBytes = width * height * 2
        val rleStream = ByteArrayOutputStream(uncompressedBytes / 2)

        // 8-byte LVGL RLE subheader
        val subHeader = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).apply {
            put(0xE0.toByte())
            put(0x21.toByte())
            put(0xA5.toByte())
            put(0x5A.toByte())
            putInt((uncompressedBytes shl 4) or 2)
        }.array()
        rleStream.write(subHeader)

        val n = pixelsRgb565.size
        var i = 0
        while (i < n) {
            var runLen = 1
            val firstPixel = pixelsRgb565[i]
            while (i + runLen < n && runLen < 127 && pixelsRgb565[i + runLen] == firstPixel) {
                runLen++
            }

            if (runLen >= 2) {
                rleStream.write(runLen and 0x7F)
                rleStream.write(firstPixel and 0xFF)
                rleStream.write((firstPixel ushr 8) and 0xFF)
                i += runLen
            } else {
                val litStart = i
                var litLen = 0
                while (i < n && litLen < 127) {
                    if (i + 1 < n && pixelsRgb565[i] == pixelsRgb565[i + 1]) {
                        break
                    }
                    i++
                    litLen++
                }
                rleStream.write(0x80 or (litLen and 0x7F))
                for (k in litStart until (litStart + litLen)) {
                    val px = pixelsRgb565[k]
                    rleStream.write(px and 0xFF)
                    rleStream.write((px ushr 8) and 0xFF)
                }
            }
        }

        val rleBytes = rleStream.toByteArray()
        val blockHeader = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN).apply {
            put(0x03.toByte()) // bitmapType = 3 (RGB565)
            put(0x04.toByte()) // compressionType = 4 (LVGL RLE V2)
            putShort(0)
            putShort(width.toShort())
            putShort(height.toShort())
            putInt(rleBytes.size)
        }.array()

        return blockHeader + rleBytes
    }

    private data class ResourceBlock(
        val key: String,
        val originalOffset: Int,
        val payload: ByteArray
    )

    private data class TableEntry(
        val index: Int,
        val resourceId: Int,
        val reserved: Int,
        val originalOffset: Int,
        val originalSize: Int
    )

    /**
     * Replaces the 3 thumbnail blocks and 3 background blocks in [templateBytes] and relocates
     * all header and resource table offsets.
     */
    fun patchTemplateBinary(
        templateBytes: ByteArray,
        newThumbBlock: ByteArray,
        newBgBlock: ByteArray,
        watchfaceId: String = DEFAULT_WALLPAPER_WATCHFACE_ID,
        watchfaceName: String = DEFAULT_WALLPAPER_NAME
    ): ByteArray {
        require(templateBytes.size >= HEADER_SIZE) { "Template watchface terlalu pendek." }
        require(templateBytes[0] == 0x5A.toByte() && templateBytes[1] == 0xA5.toByte()) {
            "Header template bukan format biner watchface Xiaomi (0x5AA5)."
        }

        val bb = ByteBuffer.wrap(templateBytes).order(ByteOrder.LITTLE_ENDIAN)

        val t1Off = HEADER_SIZE
        val t1Sz = 12 + bb.getInt(t1Off + 8)
        val t2Off = bb.getInt(0x0104)
        val t2Sz = 12 + bb.getInt(t2Off + 8)
        val t3Off = bb.getInt(0x015C)
        val t3Sz = 12 + bb.getInt(t3Off + 8)

        val entries = ArrayList<TableEntry>(RESOURCE_ENTRY_COUNT)
        for (idx in 0 until RESOURCE_ENTRY_COUNT) {
            val pos = RESOURCE_TABLE_OFFSET + idx * 16
            val rid = bb.getInt(pos)
            val z = bb.getInt(pos + 4)
            val off = bb.getInt(pos + 8)
            val sz = bb.getInt(pos + 12)
            entries.add(TableEntry(idx, rid, z, off, sz))
        }

        val blocks = ArrayList<ResourceBlock>(RESOURCE_ENTRY_COUNT + 3)
        blocks.add(ResourceBlock("t1", t1Off, newThumbBlock))
        blocks.add(ResourceBlock("t2", t2Off, newThumbBlock))
        blocks.add(ResourceBlock("t3", t3Off, newThumbBlock))

        for (e in entries) {
            val pos = RESOURCE_TABLE_OFFSET + e.index * 16
            val isFullBackground = (pos == 0x0270 || pos == 0x0490 || pos == 0x06B0)
            val blockBytes = if (isFullBackground) {
                newBgBlock
            } else {
                templateBytes.copyOfRange(e.originalOffset, e.originalOffset + e.originalSize)
            }
            blocks.add(ResourceBlock("e_${e.index}", e.originalOffset, blockBytes))
        }

        blocks.sortBy { it.originalOffset }

        val outHeader = templateBytes.copyOfRange(0, HEADER_SIZE)
        val headerBuf = ByteBuffer.wrap(outHeader).order(ByteOrder.LITTLE_ENDIAN)

        // Write watchface ID at 0x28..0x67 (64 bytes)
        if (watchfaceId.isNotBlank()) {
            val idBytes = watchfaceId.toByteArray(Charsets.UTF_8)
            for (k in 0 until 64) {
                outHeader[0x28 + k] = if (k < idBytes.size && k < 63) idBytes[k] else 0
            }
        }

        // Write watchface Name at 0x68..0xA7 (64 bytes)
        if (watchfaceName.isNotBlank()) {
            val nameBytes = watchfaceName.toByteArray(Charsets.UTF_8)
            for (k in 0 until 64) {
                outHeader[0x68 + k] = if (k < nameBytes.size && k < 63) nameBytes[k] else 0
            }
        }

        var currentOffset = HEADER_SIZE
        val newPositions = HashMap<String, Pair<Int, Int>>(blocks.size)
        for (b in blocks) {
            newPositions[b.key] = currentOffset to b.payload.size
            currentOffset += b.payload.size
        }

        val t1NewOff = newPositions.getValue("t1").first
        val t2NewOff = newPositions.getValue("t2").first
        val t3NewOff = newPositions.getValue("t3").first

        headerBuf.putInt(0x0020, t1NewOff)
        headerBuf.putInt(0x00AC, t1NewOff)
        headerBuf.putInt(0x0104, t2NewOff)
        headerBuf.putInt(0x015C, t3NewOff)

        for (e in entries) {
            val (newOff, newSz) = newPositions.getValue("e_${e.index}")
            val pos = RESOURCE_TABLE_OFFSET + e.index * 16
            headerBuf.putInt(pos, e.resourceId)
            headerBuf.putInt(pos + 4, e.reserved)
            headerBuf.putInt(pos + 8, newOff)
            headerBuf.putInt(pos + 12, newSz)
        }

        val result = ByteArray(currentOffset)
        System.arraycopy(outHeader, 0, result, 0, HEADER_SIZE)
        var writePos = HEADER_SIZE
        for (b in blocks) {
            System.arraycopy(b.payload, 0, result, writePos, b.payload.size)
            writePos += b.payload.size
        }
        return result
    }
}

