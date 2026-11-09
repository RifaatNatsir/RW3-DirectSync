package com.rw3.directsync.watchface

import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Calendar

class PhotoWatchfaceBinaryTest {
    private fun fixture(profile: PhotoWatchfaceProfile): ByteArray {
        val file = if (profile == PhotoWatchfaceProfile.HANDWRITING) File("src/main/assets/rw3_wallpaper_template.bin")
            else File("build/qa/watchface-research/${profile.originalFileName}")
        assumeTrue("Local vendor fixture not present: ${profile.title}", file.exists())
        return file.readBytes()
    }

    private fun verify(profile: PhotoWatchfaceProfile) {
        val original = fixture(profile)
        val binary = PhotoWatchfaceBinary(original, profile)
        val mainImages = binary.images()
        assertEquals(390, mainImages.getValue(0x02000000).width)
        assertEquals(450, mainImages.getValue(0x02000000).height)
        assertTrue(binary.elements().isNotEmpty())
        val lists = binary.imageLists()
        assertTrue(lists.values.flatten().isNotEmpty())
        binary.widgets().values.forEach { widget ->
            assertTrue("Missing sprite target", widget.targetId in mainImages || widget.targetId in lists)
        }
        val bg = XiaomiImageBlock.encodeRgb565(390, 450, IntArray(390 * 450) { 0xFF0088FF.toInt() })
        val thumb = XiaomiImageBlock.encodeRgb565(234, 270, IntArray(234 * 270) { 0xFF0088FF.toInt() })
        val result = binary.replacePhotos(bg, thumb)
        val bb = ByteBuffer.wrap(result).order(ByteOrder.LITTLE_ENDIAN)
        val restored = result.copyOf(original.size)
        val changed = mutableSetOf(0x20)
        for (slot in profile.photoSlots) {
            val at = binary.descriptor(slot)
            val resource = binary.resources(slot, 2).single { it.id == 0x02000000 }
            changed.addAll(listOf(resource.entry + 8, resource.entry + 12, at + 4))
            assertEquals(original.size, bb.getInt(resource.entry + 8))
            assertEquals(bg.size, bb.getInt(resource.entry + 12))
            assertEquals(original.size + bg.size, bb.getInt(at + 4))
        }
        for (word in changed) original.copyInto(restored, word, word, word + 4)
        assertArrayEquals("Existing glyphs, widgets, IDs and AOD payload must stay identical", original, restored)
        assertArrayEquals(bg + thumb, result.copyOfRange(original.size, result.size))
        // AOD is slot 1 on these multi-slot profiles; it must remain at its original addresses.
        if (profile in listOf(PhotoWatchfaceProfile.GREEN_GLASS, PhotoWatchfaceProfile.ABSTRACTION)) {
            val at = binary.descriptor(1)
            assertArrayEquals(original.copyOfRange(at, at + 88), result.copyOfRange(at, at + 88))
        }
    }

    @Test fun greenGlassPreservesItsWidgetsAndOtherSlot() = verify(PhotoWatchfaceProfile.GREEN_GLASS)
    @Test fun handwritingPreservesAllThreeLayouts() = verify(PhotoWatchfaceProfile.HANDWRITING)
    @Test fun fairyFlowerKeepsColonAndWeekdaySprites() = verify(PhotoWatchfaceProfile.FAIRY_FLOWER)
    @Test fun abstractionUpdatesAllThreeNormalVariantsAndKeepsAod() = verify(PhotoWatchfaceProfile.ABSTRACTION)
    @Test fun triangulationKeepsRotatingShapesAndClockWidgets() {
        verify(PhotoWatchfaceProfile.TRIANGULATION)
        val binary = PhotoWatchfaceBinary(fixture(PhotoWatchfaceProfile.TRIANGULATION), PhotoWatchfaceProfile.TRIANGULATION)
        val rotating = binary.widgets().values.filter { it.type == 3 }
        assertEquals(3, rotating.size)
        assertTrue(rotating.all { it.pivotX == 120 && it.pivotY == 120 })
    }

    @Test fun wrongSelectedTemplateAndCorruptFileAreRejected() {
        val original = fixture(PhotoWatchfaceProfile.FAIRY_FLOWER)
        assertThrows(IllegalArgumentException::class.java) { PhotoWatchfaceBinary(original, PhotoWatchfaceProfile.TRIANGULATION) }
        assertThrows(IllegalArgumentException::class.java) { PhotoWatchfaceBinary(original.copyOf().apply { this[lastIndex] = (this[lastIndex].toInt() xor 1).toByte() }, PhotoWatchfaceProfile.FAIRY_FLOWER) }
        assertThrows(IllegalArgumentException::class.java) { PhotoWatchfaceBinary(original.copyOf(1000), PhotoWatchfaceProfile.FAIRY_FLOWER) }
    }

    @Test fun sourcesRetainMonthDayWeekdayAndBatteryMeaning() {
        val date = Calendar.getInstance().apply { set(2026, Calendar.NOVEMBER, 8, 22, 37, 15) }
        assertEquals(11, PhotoWatchfaceBinary.value(0x1210, date, null))
        assertEquals(8, PhotoWatchfaceBinary.value(0x1218, date, null))
        assertEquals(0, PhotoWatchfaceBinary.value(0x1220, date, null)) // Sunday first, as the original sprites.
        assertEquals(22, PhotoWatchfaceBinary.value(0x1108, date, null))
        assertEquals(37, PhotoWatchfaceBinary.value(0x1110, date, null))
        assertEquals(15, PhotoWatchfaceBinary.value(0x1118, date, null))
        assertNull(PhotoWatchfaceBinary.value(0x4108, date, null))
        assertEquals(41, PhotoWatchfaceBinary.value(0x4108, date, 41))
        assertNull(PhotoWatchfaceBinary.value(0x4108, date, 101))
    }

    @Test fun abstractionRetainsRgb565GrayLightningButRejectsColoredArtwork() {
        val mark = PhotoWatchfaceProfile.ABSTRACTION.staticMarkArgb(0xFF838183.toInt())
        assertEquals(129, mark ushr 24)
        assertEquals(0xFFFFFF, mark and 0xFFFFFF)
        assertEquals(0, PhotoWatchfaceProfile.ABSTRACTION.staticMarkArgb(0xFF0066FF.toInt()))
        assertEquals(0, PhotoWatchfaceProfile.GREEN_GLASS.staticMarkArgb(0xFF838183.toInt()))
        assertEquals(-1, PhotoWatchfaceProfile.GREEN_GLASS.staticMarkArgb(-1))
    }

    @Test fun styledPacketsChangeSpriteReferencesButPreserveClockLogicInAllFiveDesigns() {
        val bg = XiaomiImageBlock.encodeRgb565(390, 450, IntArray(390 * 450) { -1 })
        val thumb = XiaomiImageBlock.encodeRgb565(234, 270, IntArray(234 * 270) { -1 })
        for (profile in PhotoWatchfaceProfile.values()) {
            val original = fixture(profile)
            val binary = PhotoWatchfaceBinary(original, profile)
            val result = binary.replacePhotos(bg, thumb, WatchfaceTextStyle(0xFFFFFF00.toInt(), true, 3))
            val bb = ByteBuffer.wrap(result).order(ByteOrder.LITTLE_ENDIAN)
            val changedWords = mutableSetOf(0x20)
            for (slot in profile.photoSlots) {
                changedWords.add(binary.descriptor(slot) + 4)
                val styledIds = binary.styledListIds(slot)
                for (resource in binary.resources(slot, 2) + binary.resources(slot, 3)) {
                    if (resource.id != 0x02000000 && resource.id !in profile.textImageIds && resource.id !in styledIds) continue
                    changedWords.add(resource.entry + 8); changedWords.add(resource.entry + 12)
                    if (resource.id in styledIds) {
                        val at = bb.getInt(resource.entry + 8)
                        val size = bb.getInt(resource.entry + 12)
                        assertTrue(at >= original.size)
                        val frames = XiaomiImageBlock.decodeImageList(result.copyOfRange(at, at + size))
                        val old = binary.frames(resource)
                        assertEquals(old.size, frames.size)
                        frames.zip(old).forEach { (new, previous) ->
                            assertEquals(previous.width, new.width); assertEquals(previous.height, new.height)
                            assertTrue("Yellow hue missing in ${profile.title}", new.argb.any {
                                (it ushr 24) > 0 && (it and 255) == 0 &&
                                    (it ushr 16 and 255) == (it ushr 8 and 255) && (it ushr 16 and 255) > 0
                            })
                        }
                    }
                }
            }
            val restored = result.copyOf(original.size)
            for (at in changedWords) original.copyInto(restored, at, at, at + 4)
            assertArrayEquals("Clock logic/geometry must be unchanged in ${profile.title}", original, restored)
        }
    }

    @Test fun spriteSpacingReadsSignedOriginalAdvance() {
        val hand = PhotoWatchfaceBinary(fixture(PhotoWatchfaceProfile.HANDWRITING), PhotoWatchfaceProfile.HANDWRITING)
        assertEquals(-4, hand.widgets().values.first { it.source == 0x1108 }.spacing)
        val tri = PhotoWatchfaceBinary(fixture(PhotoWatchfaceProfile.TRIANGULATION), PhotoWatchfaceProfile.TRIANGULATION)
        assertEquals(4, tri.widgets().values.first { it.source == 0x1108 && it.type == 1 }.spacing)
    }
}
