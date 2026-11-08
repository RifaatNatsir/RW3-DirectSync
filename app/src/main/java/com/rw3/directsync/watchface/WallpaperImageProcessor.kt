package com.rw3.directsync.watchface

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Typeface
import android.media.ExifInterface
import android.net.Uri
import android.os.Build
import android.util.Log
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import kotlin.math.max
import kotlin.math.min

/**
 * Image processing engine tailored specifically for Redmi Watch 3:
 * - Native display resolution: 390 × 450 pixels (AMOLED curved rectangular display).
 * - Aspect ratio: 390 / 450 ≈ 0.8667 (13:15).
 * - Safe single-pass stream caching and multi-tier decoding (ImageDecoder + BitmapFactory + FileDescriptor)
 *   to prevent PhotoPicker URI stream expiration and OOM on high-resolution images.
 * - Automatic EXIF orientation rotation.
 * - Accurate watchface clock overlay rendering for the live preview.
 */
object WallpaperImageProcessor {

    private const val TAG = "WallpaperProcessor"
    const val WATCH_WIDTH = 390
    const val WATCH_HEIGHT = 450
    const val ASPECT_RATIO = WATCH_WIDTH.toFloat() / WATCH_HEIGHT.toFloat()

    /**
     * Immediately copies a content URI stream to a local cache file in a single pass.
     * This prevents transient PhotoPicker URI permissions or provider processes from dying
     * before background decoding finishes.
     */
    fun cacheUriToTempFile(context: Context, uri: Uri): File? {
        val cr = context.contentResolver
        val tempFile = File(context.cacheDir, "picked_wallpaper_source.tmp")
        try {
            cr.openInputStream(uri)?.use { input ->
                FileOutputStream(tempFile).use { output ->
                    input.copyTo(output, bufferSize = 16 * 1024)
                    output.flush()
                }
            }
            if (tempFile.exists() && tempFile.length() > 0L) {
                return tempFile
            }
        } catch (e: Exception) {
            Log.w(TAG, "openInputStream copy failed for $uri, trying ParcelFileDescriptor: ${e.message}")
        }

        return try {
            cr.openFileDescriptor(uri, "r")?.use { pfd ->
                ParcelFileDescriptorInputStream(pfd).use { input ->
                    FileOutputStream(tempFile).use { output ->
                        input.copyTo(output, bufferSize = 16 * 1024)
                        output.flush()
                    }
                }
            }
            if (tempFile.exists() && tempFile.length() > 0L) tempFile else null
        } catch (e: Exception) {
            Log.e(TAG, "Failed to cache URI $uri to temp file: ${e.message}")
            null
        }
    }

    private fun ParcelFileDescriptorInputStream(pfd: android.os.ParcelFileDescriptor): InputStream {
        return android.os.ParcelFileDescriptor.AutoCloseInputStream(pfd)
    }

    /**
     * Decodes and downsamples an image from a local cached File.
     */
    fun decodeSampledBitmapFromFile(
        file: File,
        reqWidth: Int = WATCH_WIDTH * 2,
        reqHeight: Int = WATCH_HEIGHT * 2
    ): Bitmap? {
        if (!file.exists() || file.length() <= 0L) return null

        // Tier 1: Modern ImageDecoder (API 28+) - handles HEIC, WebP, PNG, JPEG & EXIF automatically
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try {
                val source = ImageDecoder.createSource(file)
                val decoded = ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                    decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                    decoder.isMutableRequired = true
                    val rawW = info.size.width
                    val rawH = info.size.height
                    var sample = 1
                    while ((rawW / (sample * 2)) >= reqWidth && (rawH / (sample * 2)) >= reqHeight) {
                        sample *= 2
                    }
                    if (sample > 1) {
                        decoder.setTargetSampleSize(sample)
                    }
                }
                if (decoded.width > 0 && decoded.height > 0) {
                    return ensureSoftwareArgbBitmap(decoded)
                }
            } catch (e: Exception) {
                Log.w(TAG, "ImageDecoder failed on temp file, falling back to BitmapFactory: ${e.message}")
            }
        }

        // Tier 2: Classic BitmapFactory + ExifInterface on local file
        try {
            val boundsOptions = BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }
            BitmapFactory.decodeFile(file.absolutePath, boundsOptions)
            val rawW = boundsOptions.outWidth
            val rawH = boundsOptions.outHeight
            if (rawW > 0 && rawH > 0) {
                var sampleSize = 1
                while ((rawW / (sampleSize * 2)) >= reqWidth && (rawH / (sampleSize * 2)) >= reqHeight) {
                    sampleSize *= 2
                }
                val decodeOptions = BitmapFactory.Options().apply {
                    inJustDecodeBounds = false
                    inSampleSize = sampleSize
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                }
                val decoded = BitmapFactory.decodeFile(file.absolutePath, decodeOptions)
                if (decoded != null) {
                    val rotation = getExifRotationDegreesFromFile(file)
                    val rotated = if (rotation != 0) rotateBitmap(decoded, rotation) else decoded
                    return ensureSoftwareArgbBitmap(rotated)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "BitmapFactory.decodeFile failed: ${e.message}")
        }

        return null
    }

    /**
     * Decodes and downsamples an image from a URI with EXIF orientation correction.
     */
    fun decodeSampledBitmap(
        context: Context,
        uri: Uri,
        reqWidth: Int = WATCH_WIDTH * 2,
        reqHeight: Int = WATCH_HEIGHT * 2
    ): Bitmap? {
        val cr = context.contentResolver

        // Tier 1: Copy URI in a single pass to temp file and decode from file
        val cachedFile = cacheUriToTempFile(context, uri)
        if (cachedFile != null) {
            val fromFile = decodeSampledBitmapFromFile(cachedFile, reqWidth, reqHeight)
            if (fromFile != null) {
                return fromFile
            }
        }

        // Tier 2: Direct ImageDecoder on ContentResolver URI (API 28+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try {
                val source = ImageDecoder.createSource(cr, uri)
                val decoded = ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                    decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                    decoder.isMutableRequired = true
                    val rawW = info.size.width
                    val rawH = info.size.height
                    var sample = 1
                    while ((rawW / (sample * 2)) >= reqWidth && (rawH / (sample * 2)) >= reqHeight) {
                        sample *= 2
                    }
                    if (sample > 1) {
                        decoder.setTargetSampleSize(sample)
                    }
                }
                if (decoded.width > 0 && decoded.height > 0) {
                    return ensureSoftwareArgbBitmap(decoded)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Direct ImageDecoder on URI failed: ${e.message}")
            }
        }

        // Tier 3: ParcelFileDescriptor decodeFileDescriptor
        try {
            cr.openFileDescriptor(uri, "r")?.use { pfd ->
                val fd = pfd.fileDescriptor
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFileDescriptor(fd, null, bounds)
                var sampleSize = 1
                if (bounds.outWidth > 0 && bounds.outHeight > 0) {
                    while ((bounds.outWidth / (sampleSize * 2)) >= reqWidth &&
                        (bounds.outHeight / (sampleSize * 2)) >= reqHeight
                    ) {
                        sampleSize *= 2
                    }
                }
                val opts = BitmapFactory.Options().apply {
                    inJustDecodeBounds = false
                    inSampleSize = sampleSize
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                }
                val decoded = BitmapFactory.decodeFileDescriptor(fd, null, opts)
                if (decoded != null) {
                    return ensureSoftwareArgbBitmap(decoded)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "decodeFileDescriptor failed for $uri: ${e.message}")
        }

        // Tier 4: Single-pass ByteArray in-memory fallback
        try {
            val bytes = cr.openInputStream(uri)?.use { it.readBytes() }
            if (bytes != null && bytes.isNotEmpty()) {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                var sampleSize = 1
                if (bounds.outWidth > 0 && bounds.outHeight > 0) {
                    while ((bounds.outWidth / (sampleSize * 2)) >= reqWidth &&
                        (bounds.outHeight / (sampleSize * 2)) >= reqHeight
                    ) {
                        sampleSize *= 2
                    }
                }
                val opts = BitmapFactory.Options().apply {
                    inJustDecodeBounds = false
                    inSampleSize = sampleSize
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                }
                val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
                if (decoded != null) {
                    val rotation = getExifRotationDegrees(ByteArrayInputStream(bytes))
                    val rotated = if (rotation != 0) rotateBitmap(decoded, rotation) else decoded
                    return ensureSoftwareArgbBitmap(rotated)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "ByteArray fallback failed for $uri: ${e.message}")
        }

        return null
    }

    private fun ensureSoftwareArgbBitmap(bitmap: Bitmap): Bitmap {
        val isHardware = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && bitmap.config == Bitmap.Config.HARDWARE
        if (!isHardware && bitmap.config == Bitmap.Config.ARGB_8888) {
            return bitmap
        }
        val converted = bitmap.copy(Bitmap.Config.ARGB_8888, true)
        return converted ?: bitmap
    }

    private fun getExifRotationDegreesFromFile(file: File): Int {
        return try {
            val exif = ExifInterface(file.absolutePath)
            when (exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90
                ExifInterface.ORIENTATION_ROTATE_180 -> 180
                ExifInterface.ORIENTATION_ROTATE_270 -> 270
                else -> 0
            }
        } catch (_: Exception) {
            0
        }
    }

    private fun getExifRotationDegrees(stream: InputStream?): Int {
        if (stream == null) return 0
        return try {
            val exif = ExifInterface(stream)
            when (exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90
                ExifInterface.ORIENTATION_ROTATE_180 -> 180
                ExifInterface.ORIENTATION_ROTATE_270 -> 270
                else -> 0
            }
        } catch (_: Exception) {
            0
        } finally {
            try { stream.close() } catch (_: Exception) {}
        }
    }

    private fun rotateBitmap(bitmap: Bitmap, degrees: Int): Bitmap {
        val matrix = Matrix().apply { postRotate(degrees.toFloat()) }
        val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        if (rotated !== bitmap) {
            bitmap.recycle()
        }
        return rotated
    }

    /**
     * Crops and scales any bitmap to the exact Redmi Watch 3 target dimensions (390 × 450)
     * using the specified zoom scale and pan offset fractions.
     */
    fun cropToWatchDimensions(
        source: Bitmap,
        scale: Float = 1.0f,
        panFractionX: Float = 0.5f,
        panFractionY: Float = 0.5f
    ): Bitmap {
        val srcW = source.width.toFloat()
        val srcH = source.height.toFloat()

        // Calculate crop rectangle with 390:450 aspect ratio
        val targetAspect = ASPECT_RATIO
        var cropW: Float
        var cropH: Float

        if (srcW / srcH > targetAspect) {
            // Source is wider than target
            cropH = srcH / scale.coerceAtLeast(1.0f)
            cropW = cropH * targetAspect
        } else {
            // Source is taller than target
            cropW = srcW / scale.coerceAtLeast(1.0f)
            cropH = cropW / targetAspect
        }

        cropW = min(cropW, srcW).coerceAtLeast(1f)
        cropH = min(cropH, srcH).coerceAtLeast(1f)

        val maxOffsetX = (srcW - cropW).coerceAtLeast(0f)
        val maxOffsetY = (srcH - cropH).coerceAtLeast(0f)

        val startX = (maxOffsetX * panFractionX.coerceIn(0f, 1f)).toInt().coerceIn(0, max(0, source.width - cropW.toInt()))
        val startY = (maxOffsetY * panFractionY.coerceIn(0f, 1f)).toInt().coerceIn(0, max(0, source.height - cropH.toInt()))

        val safeW = cropW.toInt().coerceIn(1, source.width - startX)
        val safeH = cropH.toInt().coerceIn(1, source.height - startY)

        val cropped = Bitmap.createBitmap(source, startX, startY, safeW, safeH)
        if (cropped.width == WATCH_WIDTH && cropped.height == WATCH_HEIGHT) {
            return if (cropped === source) {
                cropped.copy(Bitmap.Config.ARGB_8888, false) ?: cropped
            } else {
                cropped
            }
        }

        val scaled = Bitmap.createScaledBitmap(cropped, WATCH_WIDTH, WATCH_HEIGHT, true)
        if (cropped !== source && cropped !== scaled) {
            cropped.recycle()
        }
        return scaled
    }

    /**
     * Renders a realistic digital watch preview overlay (time, date, battery, steps)
     * with curved AMOLED screen bezel on top of the wallpaper.
     */
    fun renderClockOverlayPreview(
        wallpaper: Bitmap,
        timeText: String = "10:08",
        dateText: String = "Min, 8 Nov",
        stepText: String = "6.000"
    ): Bitmap {
        val result = Bitmap.createBitmap(WATCH_WIDTH, WATCH_HEIGHT, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(result)

        // 1. Draw scaled wallpaper
        val wpScaled = if (wallpaper.width == WATCH_WIDTH && wallpaper.height == WATCH_HEIGHT) {
            wallpaper
        } else {
            Bitmap.createScaledBitmap(wallpaper, WATCH_WIDTH, WATCH_HEIGHT, true)
        }
        canvas.drawBitmap(wpScaled, 0f, 0f, null)

        // 2. Subtle top vignette shadow for clock readability
        val shadowPaint = Paint().apply {
            color = Color.parseColor("#44000000")
            style = Paint.Style.FILL
        }
        canvas.drawRect(0f, 0f, WATCH_WIDTH.toFloat(), 180f, shadowPaint)

        // 3. Digital Clock Typography (Large, crisp, high-contrast)
        val timePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 86f
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
            setShadowLayer(8f, 0f, 4f, Color.parseColor("#99000000"))
        }

        val datePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#E0E0E0")
            textSize = 24f
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
            textAlign = Paint.Align.CENTER
            setShadowLayer(4f, 0f, 2f, Color.parseColor("#99000000"))
        }

        val metricPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#6FE0BD") // Mint accent
            textSize = 22f
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
            setShadowLayer(4f, 0f, 2f, Color.parseColor("#99000000"))
        }

        // Draw Time & Date near top (standard Redmi Watch album template layout)
        canvas.drawText(timeText, (WATCH_WIDTH / 2).toFloat(), 110f, timePaint)
        canvas.drawText(dateText, (WATCH_WIDTH / 2).toFloat(), 148f, datePaint)

        // Draw Daily Steps at bottom center
        val stepsLabel = "⚡ $stepText langkah"
        canvas.drawText(stepsLabel, (WATCH_WIDTH / 2).toFloat(), 415f, metricPaint)

        return result
    }
}
