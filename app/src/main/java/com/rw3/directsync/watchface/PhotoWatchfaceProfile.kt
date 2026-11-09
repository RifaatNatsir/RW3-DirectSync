package com.rw3.directsync.watchface

/** Exact releases acquired from Mi Fitness on the user's Redmi Watch 3. No bundled vendor assets. */
enum class PhotoWatchfaceProfile(
    val watchfaceId: String,
    val title: String,
    val fileStem: String,
    val originalSize: Int,
    val sha256: String,
    val photoSlots: List<Int>
) {
    GREEN_GLASS("265210144", "Green Glass", "GreenGlass", 1926987,
        "08274c34a003c705d1f03006801bb386b70edc0fa0046cc88517d268f70ea2bb", listOf(0)),
    FAIRY_FLOWER("265210032", "Fairy flower", "FairyFlower", 155035,
        "51311a8af80736bbf71ab81d02397981e641722ab5be0c1b257953d0196dce7f", listOf(0)),
    ABSTRACTION("265210171", "Abstraction", "Abstraction", 528821,
        "06a497756875fbd271a5f3fdf0cd83eedb28a68bc34d7e027353c212a6596351", listOf(0, 2, 3)),
    TRIANGULATION("265210196", "Triangulation", "Triangulation", 102423,
        "7cbf1f1bb5b4204a30350bdb021eed34092e64e2693833fbdf1237d9890cecb4", listOf(0)),
    HANDWRITING("265246003", "Handwriting", "Handwriting", 493733,
        "06ce03d2ec3b2e586f6bd2f490af5f3e8271d78a1b521de8386a383d34ef2c22", listOf(0, 1, 2));

    val originalFileName: String get() = "$fileStem-$watchfaceId.bin"
    val localFileName: String get() = "watchface-template-$watchfaceId.bin"
    val textImageIds: Set<Int> get() = when (this) {
        GREEN_GLASS -> emptySet()
        FAIRY_FLOWER, TRIANGULATION -> setOf(0x02000001)
        ABSTRACTION -> setOf(0x02000001, 0x02000002)
        HANDWRITING -> setOf(0x02000001, 0x02000002, 0x02000003)
    }

    internal fun glyphForStyling(image: XiaomiImageBlock.Image): XiaomiImageBlock.Image {
        if (this != FAIRY_FLOWER) return image
        // Fairy flower stores grayscale text on opaque black even in its BGRA frames.
        // Convert that original coverage mask before tinting; edited black text must stay visible.
        return image.copy(argb = IntArray(image.argb.size) { index ->
            val c = image.argb[index]
            val coverage = maxOf(c ushr 16 and 255, c ushr 8 and 255, c and 255) * (c ushr 24) / 255
            if (coverage == 0) 0 else (coverage shl 24) or 0xFFFFFF
        }, hasAlpha = true)
    }

    internal fun staticMarkArgb(color: Int): Int {
        val r = color ushr 16 and 255
        val g = color ushr 8 and 255
        val b = color and 255
        // RGB565 quantizes neutral gray differently in its 5/6/5 channels.
        val tolerance = if (this == ABSTRACTION) 8 else 0
        return if (maxOf(r, g, b) - minOf(r, g, b) <= tolerance)
            (minOf(r, g, b) shl 24) or 0xFFFFFF else 0
    }
}
