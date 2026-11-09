package com.rw3.directsync.watchface

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Path
import android.graphics.RectF
import java.util.Calendar

/** Original glyphs, separators, weekday sprites and rotating shapes remain live in the package. */
class PhotoWatchfaceTemplate(data: ByteArray, val profile: PhotoWatchfaceProfile) {
    private val binary = PhotoWatchfaceBinary(data, profile)
    private val elements = binary.elements()
    private val widgets = binary.widgets()
    private fun bitmap(image: XiaomiImageBlock.Image, transparentBlack: Boolean = false): Bitmap {
        val pixels = if (transparentBlack && !image.hasAlpha) IntArray(image.argb.size) {
            if (image.argb[it] == Color.BLACK) 0 else image.argb[it]
        } else image.argb
        return Bitmap.createBitmap(pixels, image.width, image.height, Bitmap.Config.ARGB_8888)
    }
    private val rawImages = binary.images()
    private val rawLists = binary.imageLists()
    private data class Assets(val images: Map<Int, Bitmap>, val lists: Map<Int, List<Bitmap>>, val overlay: Bitmap)
    private var cachedStyle: WatchfaceTextStyle? = null
    private var cachedAssets: Assets? = null
    private val fixedOverlay: Bitmap = Bitmap.createBitmap(390, 450, Bitmap.Config.ARGB_8888).apply {
        val source = binary.images().getValue(0x02000000)
        // Only verified marks baked into backgrounds: date slash / battery lightning icon.
        val region = when (profile) {
            PhotoWatchfaceProfile.GREEN_GLASS -> intArrayOf(180, 332, 208, 372)
            PhotoWatchfaceProfile.ABSTRACTION -> intArrayOf(280, 32, 303, 64)
            else -> null
        }
        if (region != null) for (y in region[1] until region[3]) for (x in region[0] until region[2]) {
            val c = source.argb[y * 390 + x]
            setPixel(x, y, profile.staticMarkArgb(c))
        }
    }

    @Synchronized
    private fun assets(style: WatchfaceTextStyle): Assets {
        if (cachedStyle == style) return cachedAssets!!
        val listIds = binary.styledListIds()
        val images = rawImages.mapValues { (id, image) ->
            bitmap(if (id in profile.textImageIds) WatchfaceGlyphStyler.apply(profile.glyphForStyling(image), style) else image, transparentBlack = true)
        }
        val lists = rawLists.mapValues { (id, frames) -> frames.map {
            bitmap(if (id in listIds) WatchfaceGlyphStyler.apply(profile.glyphForStyling(it), style) else it, transparentBlack = true)
        } }
        val overlay = if (profile == PhotoWatchfaceProfile.GREEN_GLASS && !style.isDefault) {
            val pixels = IntArray(390 * 450)
            fixedOverlay.getPixels(pixels, 0, 390, 0, 0, 390, 450)
            bitmap(WatchfaceGlyphStyler.apply(XiaomiImageBlock.Image(390, 450, pixels), style))
        } else fixedOverlay
        return Assets(images, lists, overlay).also { cachedStyle = style; cachedAssets = it }
    }

    fun background(photo: Bitmap, style: WatchfaceTextStyle = WatchfaceTextStyle()): Bitmap {
        require(photo.width == 390 && photo.height == 450)
        val result = Bitmap.createBitmap(390, 450, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(result)
        canvas.drawColor(Color.BLACK)
        canvas.save()
        canvas.clipPath(Path().apply { addRoundRect(RectF(0f, 0f, 390f, 450f), 86f, 86f, Path.Direction.CW) })
        canvas.drawBitmap(photo, 0f, 0f, null)
        canvas.restore()
        canvas.drawBitmap(assets(style).overlay, 0f, 0f, null)
        return result
    }

    fun preview(photo: Bitmap, time: Calendar = Calendar.getInstance(), battery: Int? = null, style: WatchfaceTextStyle = WatchfaceTextStyle()): Bitmap {
        val result = background(photo, style)
        val assets = assets(style)
        val images = assets.images
        val lists = assets.lists
        val canvas = Canvas(result)
        for (element in elements) {
            val x = element.x.toFloat()
            val y = element.y.toFloat()
            if (element.resourceId ushr 24 == 2) {
                if (element.resourceId != 0x02000000) images[element.resourceId]?.let { canvas.drawBitmap(it, x, y, null) }
                continue
            }
            val widget = widgets[element.resourceId] ?: continue
            val value = PhotoWatchfaceBinary.value(widget.source, time, battery) ?: continue
            when (widget.type) {
                1 -> {
                    val sprites = lists[widget.targetId] ?: continue
                    val digits = value.toString().let { if (widget.source == 0x4108) it else it.padStart(widget.digits, '0') }
                    var position = x
                    for (char in digits) {
                        val sprite = sprites.getOrNull(char.digitToInt()) ?: continue
                        canvas.drawBitmap(sprite, position, y, null)
                        position += sprite.width + widget.spacing
                    }
                    widget.unitId?.let { images[it] }?.let { canvas.drawBitmap(it, position, y, null) }
                }
                2 -> {
                    val index = if (widget.imageValues.isNotEmpty()) widget.imageValues.indexOfLast { it <= value }.coerceAtLeast(0) else value
                    lists[widget.targetId]?.getOrNull(index)?.let { canvas.drawBitmap(it, x, y, null) }
                }
                3 -> {
                    val shape = images[widget.targetId] ?: continue
                    val degrees = when (widget.source) {
                        0x1108 -> (value % 12) * 30f + time.get(Calendar.MINUTE) / 2f
                        0x1110, 0x1118 -> value * 6f
                        else -> continue
                    }
                    canvas.save()
                    canvas.rotate(degrees, x + widget.pivotX, y + widget.pivotY)
                    canvas.drawBitmap(shape, x, y, null)
                    canvas.restore()
                }
            }
        }
        return result
    }

    fun build(photo: Bitmap, battery: Int? = null, style: WatchfaceTextStyle = WatchfaceTextStyle()): ByteArray {
        // Template illustration date/time. Actual watch fields remain dynamic original widgets.
        val sample = Calendar.getInstance().apply { set(2026, Calendar.APRIL, 6, 8, 16, 30) }
        val thumbnail = Bitmap.createScaledBitmap(preview(photo, sample, battery, style), 234, 270, true)
        fun encode(bitmap: Bitmap): ByteArray {
            val pixels = IntArray(bitmap.width * bitmap.height)
            bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            return XiaomiImageBlock.encodeRgb565(bitmap.width, bitmap.height, pixels)
        }
        return binary.replacePhotos(encode(background(photo, style)), encode(thumbnail), style)
    }
}
