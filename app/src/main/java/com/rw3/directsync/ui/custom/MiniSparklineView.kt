package com.rw3.directsync.ui.custom

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import com.rw3.directsync.R
import com.rw3.directsync.data.db.entity.HeartRateEntity

/**
 * Compact decorative/summary sparkline for cards with tight vertical budgets
 * (Dashboard Bento HR card and Workout Session HR curve). Renders recorded
 * heart rate samples smoothly inside view bounds or a clean muted baseline
 * when fewer than 2 samples exist.
 */
class MiniSparklineView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val density = resources.displayMetrics.density
    private var lineColor = ContextCompat.getColor(context, R.color.hr_chart_line)
    private var fillColor = ContextCompat.getColor(context, R.color.hr_chart_fill)
    private val linePath = Path()
    private val fillPath = Path()
    private val points = mutableListOf<HeartRateEntity>()
    private var lastGradientHeight = -1

    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = lineColor
        strokeWidth = 2.4f * density
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val baselinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.outline_subtle)
        strokeWidth = 1.5f * density
        style = Paint.Style.STROKE
        pathEffect = DashPathEffect(floatArrayOf(5f * density, 4f * density), 0f)
    }

    private val dotOuterPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(90, 255, 136, 124)
        style = Paint.Style.FILL
    }

    private val dotInnerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = lineColor
        style = Paint.Style.FILL
    }

    fun setAccentColor(lineHexColor: Int, fillHexColor: Int = lineHexColor) {
        lineColor = lineHexColor
        fillColor = fillHexColor
        linePaint.color = lineHexColor
        dotInnerPaint.color = lineHexColor
        dotOuterPaint.color = Color.argb(90, Color.red(lineHexColor), Color.green(lineHexColor), Color.blue(lineHexColor))
        lastGradientHeight = -1
        invalidate()
    }

    fun setRecords(records: List<HeartRateEntity>) {
        points.clear()
        val valid = records.filter { it.bpm in 30..240 }
            .distinctBy { it.timestamp }
            .sortedBy { it.timestamp }
        // Downsample to max 48 points so smooth curves stay crisp on small cards.
        if (valid.size > 48) {
            val step = valid.size / 48.0
            for (i in 0 until 48) {
                points.add(valid[(i * step).toInt().coerceAtMost(valid.lastIndex)])
            }
            if (points.lastOrNull() != valid.last()) points.add(valid.last())
        } else {
            points.addAll(valid)
        }
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val padH = 4f * density
        val padV = 4f * density
        val left = paddingLeft + padH
        val right = width - paddingRight - padH
        val top = paddingTop + padV
        val bottom = height - paddingBottom - padV
        if (right <= left || bottom <= top) return

        if (points.size < 2) {
            val midY = (top + bottom) / 2f
            canvas.drawLine(left, midY, right, midY, baselinePaint)
            return
        }

        if (lastGradientHeight != height) {
            fillPaint.shader = LinearGradient(
                0f, top, 0f, bottom,
                Color.argb(96, Color.red(fillColor), Color.green(fillColor), Color.blue(fillColor)),
                Color.argb(0, Color.red(fillColor), Color.green(fillColor), Color.blue(fillColor)),
                Shader.TileMode.CLAMP
            )
            lastGradientHeight = height
        }

        val minBpm = (points.minOf { it.bpm } - 6).coerceAtLeast(30)
        val maxBpm = (points.maxOf { it.bpm } + 6).coerceAtLeast(minBpm + 12)
        val spanBpm = (maxBpm - minBpm).toFloat()

        val minTime = points.first().timestamp
        val maxTime = points.last().timestamp
        val spanTime = (maxTime - minTime).coerceAtLeast(1L)

        fun mapX(index: Int, t: Long): Float {
            return if (maxTime > minTime) {
                left + ((t - minTime).toFloat() / spanTime) * (right - left)
            } else {
                left + (index.toFloat() / (points.size - 1)) * (right - left)
            }
        }

        fun mapY(bpm: Int): Float {
            val norm = (bpm - minBpm) / spanBpm
            return bottom - norm * (bottom - top)
        }

        linePath.reset()
        fillPath.reset()
        var lastX = mapX(0, points[0].timestamp)
        var lastY = mapY(points[0].bpm)
        linePath.moveTo(lastX, lastY)
        fillPath.moveTo(lastX, bottom)
        fillPath.lineTo(lastX, lastY)

        for (i in 1 until points.size) {
            val curX = mapX(i, points[i].timestamp)
            val curY = mapY(points[i].bpm)
            val midX = (lastX + curX) / 2f
            val midY = (lastY + curY) / 2f
            linePath.quadTo(lastX, lastY, midX, midY)
            fillPath.quadTo(lastX, lastY, midX, midY)
            lastX = curX
            lastY = curY
        }
        linePath.lineTo(lastX, lastY)
        fillPath.lineTo(lastX, lastY)
        fillPath.lineTo(lastX, bottom)
        fillPath.close()

        canvas.drawPath(fillPath, fillPaint)
        canvas.drawPath(linePath, linePaint)

        canvas.drawCircle(lastX, lastY, 5.5f * density, dotOuterPaint)
        canvas.drawCircle(lastX, lastY, 2.8f * density, dotInnerPaint)
    }
}

