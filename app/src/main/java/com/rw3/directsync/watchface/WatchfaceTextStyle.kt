package com.rw3.directsync.watchface

import kotlin.math.exp
import kotlin.math.roundToInt

data class WatchfaceTextStyle(val color: Int? = null, val shadow: Boolean = false, val feather: Int = 3) {
    init { require(feather in 1..6); require(color == null || color ushr 24 == 255) }
    val isDefault: Boolean get() = color == null && !shadow
}

/** Styles actual glyph pixels, preserving frame dimensions and the watch's original digit advance. */
object WatchfaceGlyphStyler {
    fun apply(image: XiaomiImageBlock.Image, style: WatchfaceTextStyle): XiaomiImageBlock.Image {
        val w = image.width
        val h = image.height
        val original = if (image.hasAlpha) image.argb else IntArray(image.argb.size) {
            if (image.argb[it] == 0xFF000000.toInt()) 0 else image.argb[it]
        }
        val alpha = FloatArray(original.size) { (original[it] ushr 24).toFloat() }
        val blurred = if (style.shadow) blur(alpha, w, h, style.feather) else null
        val pixels = IntArray(original.size) { i ->
            val foreground = original[i]
            val a = foreground ushr 24
            val shadow = if (blurred != null) (blurred[i] * 0.85f).roundToInt() else 0
            val outAlpha = a + shadow * (255 - a) / 255
            if (outAlpha == 0) 0 else {
                val color = style.color ?: foreground
                // Straight ARGB result of the colored foreground over a black feathered shadow.
                val r = (color ushr 16 and 255) * a / outAlpha
                val g = (color ushr 8 and 255) * a / outAlpha
                val b = (color and 255) * a / outAlpha
                (outAlpha shl 24) or (r shl 16) or (g shl 8) or b
            }
        }
        return XiaomiImageBlock.Image(w, h, pixels, hasAlpha = true)
    }

    private fun blur(alpha: FloatArray, w: Int, h: Int, radius: Int): FloatArray {
        val sigma = radius / 2f
        val weights = FloatArray(radius * 2 + 1) { n -> exp(-((n - radius) * (n - radius)) / (2f * sigma * sigma)) }
        val sum = weights.sum()
        for (i in weights.indices) weights[i] /= sum
        val horizontal = FloatArray(alpha.size)
        val output = FloatArray(alpha.size)
        for (y in 0 until h) for (x in 0 until w) {
            var value = 0f
            for (dx in -radius..radius) if (x + dx in 0 until w)
                value += alpha[y * w + x + dx] * weights[dx + radius]
            horizontal[y * w + x] = value
        }
        for (y in 0 until h) for (x in 0 until w) {
            var value = 0f
            for (dy in -radius..radius) if (y + dy in 0 until h)
                value += horizontal[(y + dy) * w + x] * weights[dy + radius]
            output[y * w + x] = value
        }
        return output
    }
}
