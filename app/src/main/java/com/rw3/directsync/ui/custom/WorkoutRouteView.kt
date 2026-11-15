package com.rw3.directsync.ui.custom

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import com.rw3.directsync.R
import com.rw3.directsync.data.MiFitnessHealthMapper.RoutePoint
import kotlin.math.cos
import kotlin.math.max

/**
 * Renders a dark-mode projected GPS track polyline directly from real Health Connect
 * ExerciseRoute points, including subtle grid lines and start/end markers.
 */
class WorkoutRouteView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val density = resources.displayMetrics.density
    private var points: List<RoutePoint> = emptyList()

    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(36, 173, 186, 204)
        strokeWidth = 1f * density
        style = Paint.Style.STROKE
    }

    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(72, 111, 224, 189)
        strokeWidth = 8f * density
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        style = Paint.Style.STROKE
    }

    private val routePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.primary_mint)
        strokeWidth = 3.2f * density
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        style = Paint.Style.STROKE
    }

    private val startFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.primary_mint)
        style = Paint.Style.FILL
    }

    private val endFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.heart_rate_coral)
        style = Paint.Style.FILL
    }

    private val markerStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        strokeWidth = 2f * density
        style = Paint.Style.STROKE
    }

    private val path = Path()

    fun setRoute(routePoints: List<RoutePoint>) {
        points = routePoints
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        // Subtle topographic backdrop lines
        val step = 28f * density
        var gx = step
        while (gx < w) {
            canvas.drawLine(gx, 0f, gx, h, gridPaint)
            gx += step
        }
        var gy = step
        while (gy < h) {
            canvas.drawLine(0f, gy, w, gy, gridPaint)
            gy += step
        }

        if (points.size < 2) return

        val midLatRad = Math.toRadians(points.sumOf { it.lat } / points.size)
        val lonScale = cos(midLatRad).coerceAtLeast(0.2)

        val minX = points.minOf { it.lon * lonScale }
        val maxX = points.maxOf { it.lon * lonScale }
        val minY = points.minOf { it.lat }
        val maxY = points.maxOf { it.lat }

        val spanX = max(1e-6, maxX - minX)
        val spanY = max(1e-6, maxY - minY)

        val pad = 22f * density
        val availW = (w - 2 * pad).coerceAtLeast(1f)
        val availH = (h - 2 * pad).coerceAtLeast(1f)
        val scale = minOf(availW / spanX.toFloat(), availH / spanY.toFloat())
        val offsetX = (w - spanX.toFloat() * scale) / 2f
        val offsetY = (h - spanY.toFloat() * scale) / 2f

        fun toScreenX(p: RoutePoint): Float = offsetX + ((p.lon * lonScale - minX).toFloat() * scale)
        fun toScreenY(p: RoutePoint): Float = h - offsetY - ((p.lat - minY).toFloat() * scale)

        path.reset()
        points.forEachIndexed { idx, p ->
            val sx = toScreenX(p)
            val sy = toScreenY(p)
            if (idx == 0) path.moveTo(sx, sy) else path.lineTo(sx, sy)
        }

        canvas.drawPath(path, glowPaint)
        canvas.drawPath(path, routePaint)

        val r = 5.5f * density
        val firstX = toScreenX(points.first())
        val firstY = toScreenY(points.first())
        canvas.drawCircle(firstX, firstY, r, startFillPaint)
        canvas.drawCircle(firstX, firstY, r, markerStrokePaint)

        val lastX = toScreenX(points.last())
        val lastY = toScreenY(points.last())
        canvas.drawCircle(lastX, lastY, r, endFillPaint)
        canvas.drawCircle(lastX, lastY, r, markerStrokePaint)
    }
}

