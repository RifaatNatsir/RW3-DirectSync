package com.rw3.directsync.watchface

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.Calendar

/** Bounded reader/patcher for the four verified RW3 templates, not arbitrary imported firmware. */
class PhotoWatchfaceBinary(input: ByteArray, val profile: PhotoWatchfaceProfile) {
    private val original = input.copyOf()
    init {
        require(original.size == profile.originalSize &&
            MessageDigest.getInstance("SHA-256").digest(original).joinToString("") { "%02x".format(it) } == profile.sha256) {
            "Pilih berkas ${profile.title} asli ID ${profile.watchfaceId}. Versi template ini belum didukung."
        }
        require(u32(0) == 0x1234A55A && u32(0x10) == 0x800)
        require(String(original, 0x28, 64, Charsets.UTF_8).trimEnd('\u0000') == profile.watchfaceId)
    }

    data class Resource(val entry: Int, val id: Int, val offset: Int, val size: Int)
    data class Element(val resourceId: Int, val x: Int, val y: Int)
    data class Widget(val source: Int, val type: Int, val targetId: Int, val digits: Int,
        val spacing: Int, val unitId: Int?, val pivotX: Int, val pivotY: Int, val imageValues: List<Int>)

    private fun u16(offset: Int): Int {
        require(offset >= 0 && offset <= original.size - 2)
        return ByteBuffer.wrap(original).order(ByteOrder.LITTLE_ENDIAN).getShort(offset).toInt() and 65535
    }
    private fun u32(offset: Int): Int {
        require(offset >= 0 && offset <= original.size - 4)
        return ByteBuffer.wrap(original).order(ByteOrder.LITTLE_ENDIAN).getInt(offset)
    }
    fun descriptor(slot: Int): Int {
        require(slot in 0 until (original[0x1C].toInt() and 255))
        return 0xA8 + 4 * ((original[0x18].toInt() and 255) + (original[0x1D].toInt() and 255)) + slot * 88
    }
    fun resources(slot: Int, group: Int): List<Resource> {
        require(group in 0..9)
        val at = descriptor(slot) + 8 + group * 8
        val count = u32(at)
        val table = u32(at + 4)
        require(count in 0..512 && table >= 0 && table.toLong() + count * 16L <= original.size)
        return List(count) { index ->
            val entry = table + index * 16
            val offset = u32(entry + 8)
            val size = u32(entry + 12)
            require(offset >= 0 && size >= 0 && offset.toLong() + size <= original.size)
            Resource(entry, u32(entry), offset, size)
        }
    }
    fun body(resource: Resource): ByteArray = original.copyOfRange(resource.offset, resource.offset + resource.size)
    fun elements(slot: Int = 0): List<Element> = resources(slot, 0).map {
        require(it.size >= 8)
        Element(u32(it.offset), u16(it.offset + 4), u16(it.offset + 6))
    }
    fun widgets(slot: Int = 0): Map<Int, Widget> = resources(slot, 7).associate { r ->
        require(r.size >= 16)
        val at = r.offset
        r.id to Widget(u16(at), (original[at + 3].toInt() and 255) ushr 4, u32(at + 8),
            original[at + 2].toInt() and 255, original[at + 13].toInt(),
            if (r.size >= 20 && u32(at + 16) ushr 24 == 2) u32(at + 16) else null,
            if (r.size >= 32) u16(at + 20) else 0, if (r.size >= 32) u16(at + 22) else 0,
            if (u16(at + 12) == 0x200) List((r.size - 16) / 4) { u32(at + 16 + it * 4) } else emptyList())
    }
    fun images(slot: Int = 0): Map<Int, XiaomiImageBlock.Image> = resources(slot, 2).associate {
        it.id to XiaomiImageBlock.decode(body(it))
    }
    fun frames(resource: Resource): List<XiaomiImageBlock.Image> {
        val at = resource.offset
        require(resource.size >= 20)
        val count = original[at + 1].toInt() and 255
        require(count in 1..60 && resource.size >= 12 + count * 4)
        val width = u16(at + 4)
        val height = u16(at + 6)
        var offset = at + 12 + count * 4
        val result = List(count) { index ->
            val size = u32(at + 12 + index * 4)
            require(size >= 8 && size <= at + resource.size - offset)
            val block = ByteBuffer.allocate(12 + size).order(ByteOrder.LITTLE_ENDIAN).apply {
                put(0); put(4); putShort(0); putShort(width.toShort()); putShort(height.toShort()); putInt(size)
                put(original, offset, size)
            }.array()
            offset += size
            XiaomiImageBlock.decode(block)
        }
        require(offset == at + resource.size)
        return result
    }
    fun imageLists(slot: Int = 0): Map<Int, List<XiaomiImageBlock.Image>> = resources(slot, 3).associate {
        it.id to frames(it)
    }

    /** Keep resource bytes, ID, and original addresses intact; append and redirect photo blocks. */
    fun replacePhotos(background: ByteArray, preview: ByteArray, style: WatchfaceTextStyle = WatchfaceTextStyle()): ByteArray {
        val bg = XiaomiImageBlock.decode(background)
        val thumb = XiaomiImageBlock.decode(preview)
        require(bg.width == 390 && bg.height == 450 && thumb.width == 234 && thumb.height == 270)
        val styled = linkedMapOf<Int, ByteArray>()
        if (!style.isDefault) profile.photoSlots.forEach { slot ->
            val listIds = styledListIds(slot)
            resources(slot, 3).filter { it.id in listIds }.forEach { resource ->
                styled[resource.entry] = XiaomiImageBlock.encodeImageList(frames(resource).map { WatchfaceGlyphStyler.apply(profile.glyphForStyling(it), style) })
            }
            resources(slot, 2).filter { it.id in profile.textImageIds }.forEach { resource ->
                styled[resource.entry] = XiaomiImageBlock.encodeArgb8888(WatchfaceGlyphStyler.apply(profile.glyphForStyling(XiaomiImageBlock.decode(body(resource))), style))
            }
        }
        val size = original.size + background.size + preview.size + styled.values.sumOf { it.size }
        require(size <= 10 * 1024 * 1024) { "Watchface hasil edit terlalu besar." }
        val result = ByteArray(size)
        original.copyInto(result)
        background.copyInto(result, original.size)
        preview.copyInto(result, original.size + background.size)
        val bb = ByteBuffer.wrap(result).order(ByteOrder.LITTLE_ENDIAN)
        var cursor = original.size + background.size + preview.size
        styled.forEach { (entry, payload) ->
            payload.copyInto(result, cursor)
            bb.putInt(entry + 8, cursor); bb.putInt(entry + 12, payload.size)
            cursor += payload.size
        }
        bb.putInt(0x20, original.size + background.size)
        profile.photoSlots.forEach { slot ->
            val descriptor = descriptor(slot)
            val backgroundId = u32(descriptor)
            val resource = resources(slot, 2).single { it.id == backgroundId }
            val image = XiaomiImageBlock.decode(body(resource))
            require(image.width == 390 && image.height == 450)
            bb.putInt(resource.entry + 8, original.size)
            bb.putInt(resource.entry + 12, background.size)
            require(u32(descriptor + 4) > 0)
            bb.putInt(descriptor + 4, original.size + background.size)
        }
        return result
    }

    fun styledListIds(slot: Int = 0): Set<Int> = widgets(slot).values
        .filter { it.type == 1 || it.source == 0x1220 }.map { it.targetId }.toSet()

    companion object {
        /** RW3 source IDs, from Mi Create's redmi_watch_3 sources.json (little endian). */
        fun value(source: Int, time: Calendar, battery: Int?): Int? = when (source) {
            0x1108 -> time.get(Calendar.HOUR_OF_DAY)
            0x1110 -> time.get(Calendar.MINUTE)
            0x1118 -> time.get(Calendar.SECOND)
            0x1210 -> time.get(Calendar.MONTH) + 1
            0x1218 -> time.get(Calendar.DAY_OF_MONTH)
            0x1220 -> time.get(Calendar.DAY_OF_WEEK) - 1
            0x4108 -> battery?.takeIf { it in 0..100 }
            else -> null
        }
    }
}
