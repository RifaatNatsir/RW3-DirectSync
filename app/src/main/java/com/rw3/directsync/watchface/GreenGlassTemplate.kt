package com.rw3.directsync.watchface

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.RectF
import java.util.Calendar

/** Loaded locally from the user's original file, never distributed as an app asset. */
class GreenGlassTemplate(private val original: ByteArray) {
    init { GreenGlassBinary.validateOriginal(original) }
    private val originalBackground = GreenGlassBinary.background(original)
    private val digits = Array(4) { list -> Array(10) { value ->
        val image = GreenGlassBinary.digit(original, list, value)
        Bitmap.createBitmap(image.argb, image.width, image.height, Bitmap.Config.ARGB_8888)
    } }
    private val slash = Bitmap.createBitmap(390, 450, Bitmap.Config.ARGB_8888).apply {
        // The date separator is baked into the verified background. Extract its alpha mask.
        for (y in 332 until 372) for (x in 180 until 208) {
            val c = originalBackground.argb[y * 390 + x]
            if (Color.red(c) == Color.green(c) && Color.green(c) == Color.blue(c))
                setPixel(x, y, Color.argb(Color.red(c), 255, 255, 255))
        }
    }

    fun background(photo: Bitmap): Bitmap {
        require(photo.width == 390 && photo.height == 450)
        val result = Bitmap.createBitmap(390, 450, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(result)
        canvas.drawColor(Color.BLACK)
        canvas.save()
        val path = android.graphics.Path().apply { addRoundRect(RectF(0f, 0f, 390f, 450f), 86f, 86f, android.graphics.Path.Direction.CW) }
        canvas.clipPath(path)
        canvas.drawBitmap(photo, 0f, 0f, null)
        canvas.restore()
        canvas.drawBitmap(slash, 0f, 0f, null)
        return result
    }

    fun preview(photo: Bitmap, time: Calendar = Calendar.getInstance()): Bitmap {
        val result = background(photo)
        val canvas = Canvas(result)
        // Coordinates are from Green Glass's element records, not a substitute Android font.
        fun number(list: Int, value: Int, x: Float, y: Float) {
            val first = digits[list][value / 10]
            canvas.drawBitmap(first, x, y, null)
            canvas.drawBitmap(digits[list][value % 10], x + first.width, y, null)
        }
        number(0, time.get(Calendar.MONTH) + 1, 137f, 332f)
        number(1, time.get(Calendar.DAY_OF_MONTH), 209f, 332f)
        number(2, time.get(Calendar.HOUR_OF_DAY), 128f, 79f)
        number(3, time.get(Calendar.MINUTE), 128f, 197f)
        return result
    }

    fun build(photo: Bitmap): ByteArray {
        val bg = background(photo)
        // Match original thumbnail content (08:16, 04/06) while retaining live glyph widgets.
        val time = Calendar.getInstance().apply { set(Calendar.HOUR_OF_DAY, 8); set(Calendar.MINUTE, 16); set(Calendar.DAY_OF_MONTH, 4); set(Calendar.MONTH, 5) }
        val thumb = Bitmap.createScaledBitmap(preview(photo, time), 234, 270, true)
        fun encode(bitmap: Bitmap): ByteArray {
            val colors = IntArray(bitmap.width * bitmap.height)
            bitmap.getPixels(colors, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            return XiaomiImageBlock.encodeRgb565(bitmap.width, bitmap.height, colors)
        }
        return GreenGlassBinary.replaceMain(original, encode(bg), encode(thumb))
    }
}
