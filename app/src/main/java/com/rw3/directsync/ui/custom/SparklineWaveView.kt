package com.rw3.directsync.ui.custom

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View
import com.rw3.directsync.data.db.entity.HeartRateEntity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Hardware-accelerated 24-Hour Heart Rate Timeline Chart:
 * - Plots real PPG & BLE Heart Rate points over the past 24 hours.
 * - Displays horizontal BPM guidelines (60, 100, 140 BPM) with labels.
 * - Displays vertical 6-hour time markers (-24j, -18j, -12j, -6j, Kini).
 * - Smooth cubic Bézier spline with modern gradient area fill under the curve.
 * - Zero external chart libraries to keep APK size < 3.5 MB and render at 60/120 FPS.
 */
class SparklineWaveView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    data class HeartRatePoint(
        val timestamp: Long,
        val bpm: Int
    )

    private val density = resources.displayMetrics.density

    private val strokeColor = Color.parseColor("#FF7A73")
    private val gradientStartColor = Color.argb(100, 255, 122, 115) // ~39% alpha coral
    private val gradientEndColor = Color.argb(0, 255, 122, 115)     // 0% transparent

    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.2f * density
        color = strokeColor
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f * density
        color = Color.parseColor("#1E273D")
        pathEffect = DashPathEffect(floatArrayOf(4f * density, 4f * density), 0f)
    }

    private val axisTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#64748B")
        textSize = 9.5f * density
        typeface = Typeface.MONOSPACE
    }

    private val emptyTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#64748B")
        textSize = 11f * density
        textAlign = Paint.Align.CENTER
    }

    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = strokeColor
    }

    private val dotOuterPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.argb(60, 255, 122, 115)
    }

    private val linePath = Path()
    private val fillPath = Path()
    private val gridPath = Path()

    private val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())

    private var heartRatePoints: MutableList<HeartRatePoint> = mutableListOf()
    private var cachedGradientHeight: Float = 0f

    /**
     * Replaces the data with a 24-hour collection of HeartRateEntity from Room SQLite.
     */
    fun setHeartRateRecords(records: List<HeartRateEntity>) {
        val mapped = records
            .filter { it.bpm in 30..240 }
            .map { HeartRatePoint(it.timestamp, it.bpm) }
            .sortedBy { it.timestamp }
        heartRatePoints = mapped.toMutableList()
        invalidate()
    }

    /**
     * Sets points directly.
     */
    fun setHeartRateData(points: List<HeartRatePoint>) {
        heartRatePoints = points.filter { it.bpm in 30..240 }.sortedBy { it.timestamp }.toMutableList()
        invalidate()
    }

    /**
     * Appends or updates the latest real-time BPM reading at current timestamp.
     */
    fun updateBpm(bpm: Int) {
        if (bpm !in 30..240) return
        val now = System.currentTimeMillis()
        // If last point was less than 5 seconds ago, update it; otherwise append
        val last = heartRatePoints.lastOrNull()
        if (last != null && now - last.timestamp < 5_000L) {
            heartRatePoints[heartRatePoints.size - 1] = HeartRatePoint(now, bpm)
        } else {
            heartRatePoints.add(HeartRatePoint(now, bpm))
            // Prune records older than 24h
            val cutoff = now - 24 * 3600 * 1000L
            heartRatePoints.removeAll { it.timestamp < cutoff }
        }
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (h > 0) {
            cachedGradientHeight = h.toFloat()
            fillPaint.shader = LinearGradient(
                0f, 0f, 0f, cachedGradientHeight,
                gradientStartColor, gradientEndColor,
                Shader.TileMode.CLAMP
            )
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0 || h <= 0) return

        val paddingLeft = 28f * density
        val paddingRight = 10f * density
        val paddingTop = 12f * density
        val paddingBottom = 18f * density

        val plotW = w - paddingLeft - paddingRight
        val plotH = h - paddingTop - paddingBottom
        if (plotW <= 0 || plotH <= 0) return

        val now = System.currentTimeMillis()
        val window24h = 24 * 3600 * 1000L
        val startTime = now - window24h

        // 1. Determine Y-axis BPM Range (Dynamic with minimum healthy bounds)
        var minY = 45
        var maxY = 145
        if (heartRatePoints.isNotEmpty()) {
            val minBpm = heartRatePoints.minOf { it.bpm }
            val maxBpm = heartRatePoints.maxOf { it.bpm }
            minY = minOf(minY, (minBpm - 8).coerceAtLeast(35))
            maxY = maxOf(maxY, (maxBpm + 8).coerceAtMost(220))
            if (maxY - minY < 40) maxY = minY + 40
        }

        fun bpmToY(bpm: Int): Float {
            val ratio = (bpm - minY).toFloat() / (maxY - minY).toFloat()
            return paddingTop + (1f - ratio.coerceIn(0f, 1f)) * plotH
        }

        fun timeToX(timestamp: Long): Float {
            val progress = (timestamp - startTime).toFloat() / window24h.toFloat()
            return paddingLeft + progress.coerceIn(0f, 1f) * plotW
        }

        // 2. Draw Horizontal BPM Grid Lines & Y Labels (60, 100, 140)
        val guidelineBpms = intArrayOf(60, 100, 140)
        for (gBpm in guidelineBpms) {
            if (gBpm in minY..maxY) {
                val y = bpmToY(gBpm)
                gridPath.reset()
                gridPath.moveTo(paddingLeft, y)
                gridPath.lineTo(w - paddingRight, y)
                canvas.drawPath(gridPath, gridPaint)

                // Label on left margin
                axisTextPaint.textAlign = Paint.Align.RIGHT
                canvas.drawText("$gBpm", paddingLeft - (4f * density), y + (3.5f * density), axisTextPaint)
            }
        }

        // 3. Draw Vertical 6-Hour Time Markers & Bottom X Labels (-24j, -18j, -12j, -6j, Kini)
        val stepCount = 4
        axisTextPaint.textAlign = Paint.Align.CENTER
        for (i in 0..stepCount) {
            val x = paddingLeft + (i.toFloat() / stepCount) * plotW
            val markTime = startTime + (i * 6 * 3600 * 1000L)

            // Dotted vertical grid mark
            gridPath.reset()
            gridPath.moveTo(x, paddingTop)
            gridPath.lineTo(x, h - paddingBottom)
            canvas.drawPath(gridPath, gridPaint)

            // Label at bottom: Clock hour with "-24j" start and "Kini" end
            val label = when (i) {
                0 -> timeFormat.format(Date(markTime)) + " (-24j)"
                stepCount -> "Kini"
                else -> timeFormat.format(Date(markTime))
            }
            canvas.drawText(label, x, h - (3f * density), axisTextPaint)
        }

        // 4. Render 24-Hour Heart Rate Waveform
        val validPoints = heartRatePoints.filter { it.timestamp >= startTime }

        if (validPoints.size >= 2) {
            linePath.reset()
            fillPath.reset()

            val xCoords = FloatArray(validPoints.size)
            val yCoords = FloatArray(validPoints.size)

            for (i in validPoints.indices) {
                xCoords[i] = timeToX(validPoints[i].timestamp)
                yCoords[i] = bpmToY(validPoints[i].bpm)
            }

            linePath.moveTo(xCoords[0], yCoords[0])
            fillPath.moveTo(xCoords[0], h - paddingBottom)
            fillPath.lineTo(xCoords[0], yCoords[0])

            // Smooth cubic Bézier spline interpolation
            for (i in 1 until validPoints.size) {
                val prevX = xCoords[i - 1]
                val prevY = yCoords[i - 1]
                val curX = xCoords[i]
                val curY = yCoords[i]

                val midX = (prevX + curX) / 2f
                linePath.cubicTo(midX, prevY, midX, curY, curX, curY)
                fillPath.cubicTo(midX, prevY, midX, curY, curX, curY)
            }

            val lastIdx = validPoints.size - 1
            fillPath.lineTo(xCoords[lastIdx], h - paddingBottom)
            fillPath.close()

            // Draw Area Fill & Line
            canvas.drawPath(fillPath, fillPaint)
            canvas.drawPath(linePath, linePaint)

            // Draw Point Markers if points <= 35
            if (validPoints.size <= 35) {
                for (i in validPoints.indices) {
                    canvas.drawCircle(xCoords[i], yCoords[i], 2.5f * density, dotPaint)
                }
            }

            // Draw prominent latest reading pulse dot at end
            val lastX = xCoords[lastIdx]
            val lastY = yCoords[lastIdx]
            canvas.drawCircle(lastX, lastY, 5.5f * density, dotOuterPaint)
            canvas.drawCircle(lastX, lastY, 2.8f * density, dotPaint)

        } else if (validPoints.size == 1) {
            // Single point received: draw reference line through point
            val x = timeToX(validPoints[0].timestamp)
            val y = bpmToY(validPoints[0].bpm)

            gridPath.reset()
            gridPath.moveTo(paddingLeft, y)
            gridPath.lineTo(w - paddingRight, y)
            canvas.drawPath(gridPath, linePaint)

            canvas.drawCircle(x, y, 6f * density, dotOuterPaint)
            canvas.drawCircle(x, y, 3f * density, dotPaint)

            emptyTextPaint.textSize = 10f * density
            canvas.drawText("Pengukuran tunggal: ${validPoints[0].bpm} BPM", w / 2f, h / 2f - (8f * density), emptyTextPaint)

        } else {
            // No data in last 24h: draw subtle circadian rhythm baseline simulation
            drawCircadianBaselinePreview(canvas, paddingLeft, paddingTop, plotW, plotH)
        }
    }

    private fun drawCircadianBaselinePreview(
        canvas: Canvas,
        startX: Float,
        topY: Float,
        width: Float,
        height: Float
    ) {
        val previewPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 1.6f * density
            color = Color.parseColor("#334155")
            pathEffect = DashPathEffect(floatArrayOf(5f * density, 5f * density), 0f)
        }

        val path = Path()
        val segments = 16
        val midY = topY + (height * 0.58f)

        for (i in 0..segments) {
            val progress = i.toFloat() / segments
            val x = startX + progress * width
            // Circadian curve dipping in the morning/night and slightly higher in afternoon
            val wave = Math.sin((progress * 2 * Math.PI) - (Math.PI / 2)).toFloat()
            val y = midY + (wave * 8f * density)

            if (i == 0) {
                path.moveTo(x, y)
            } else {
                path.lineTo(x, y)
            }
        }
        canvas.drawPath(path, previewPaint)

        canvas.drawText(
            "Belum ada rekaman riwayat 24 jam",
            startX + (width / 2f),
            midY - (12f * density),
            emptyTextPaint
        )
    }
}
