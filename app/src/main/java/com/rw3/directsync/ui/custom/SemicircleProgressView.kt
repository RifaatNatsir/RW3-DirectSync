package com.rw3.directsync.ui.custom

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View

/**
 * Lightweight hardware-accelerated Semicircle Progress Gauge:
 * Renders an upward 180-degree curved meter with round caps and custom stroke color.
 */
class SemicircleProgressView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val density = resources.displayMetrics.density
    private var strokeWidthPx = 8.5f * density

    var progress: Float = 0f
        set(value) {
            field = value.coerceIn(0f, 100f)
            invalidate()
        }

    var progressColor: Int = Color.parseColor("#4EDEA3")
        set(value) {
            field = value
            progressPaint.color = value
            invalidate()
        }

    var trackColor: Int = Color.parseColor("#171F33")
        set(value) {
            field = value
            trackPaint.color = value
            invalidate()
        }

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeWidth = strokeWidthPx
        color = trackColor
    }

    private val progressPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeWidth = strokeWidthPx
        color = progressColor
    }

    private val arcBounds = RectF()

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val pad = strokeWidthPx / 2f + 4f * density
        // For a 180-deg semicircle: width is 2*radius, height is radius
        val diameter = (w - 2 * pad).coerceAtLeast(10f)
        arcBounds.set(
            (w - diameter) / 2f,
            h - diameter / 2f - pad,
            (w + diameter) / 2f,
            h + diameter / 2f - pad
        )
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (arcBounds.isEmpty) return

        // 1. Draw background semicircle track (180 to 0 degrees)
        canvas.drawArc(arcBounds, 180f, 180f, false, trackPaint)

        // 2. Draw active progress arc
        if (progress > 0f) {
            val sweep = 180f * (progress / 100f)
            canvas.drawArc(arcBounds, 180f, sweep, false, progressPaint)
        }
    }
}

