package com.rw3.directsync.ui.custom

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import com.rw3.directsync.R

/** Proportions of reported Deep/Light/REM minutes. Unknown phases have a neutral track. */
class SleepPhaseBarView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var minutes: List<Int>? = null
    private val colors = listOf(R.color.sleep_deep, R.color.sleep_light, R.color.sleep_rem)
    fun setPhases(deep: Int?, light: Int?, rem: Int?) {
        minutes = if (deep != null && light != null && rem != null) listOf(deep, light, rem).map { it.coerceAtLeast(0) } else null
        contentDescription = minutes?.let { "Deep ${it[0]} menit, Light ${it[1]} menit, REM ${it[2]} menit" }
            ?: "Rincian fase tidur belum tersedia"
        invalidate()
    }
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val bounds = RectF(0f, 0f, width.toFloat(), height.toFloat())
        val radius = height / 2f
        paint.color = ContextCompat.getColor(context, R.color.surface_container_highest)
        canvas.drawRoundRect(bounds, radius, radius, paint)
        val values = minutes ?: return
        val total = values.sum().takeIf { it > 0 } ?: return
        canvas.save()
        canvas.clipPath(Path().apply { addRoundRect(bounds, radius, radius, Path.Direction.CW) })
        var x = 0f
        values.forEachIndexed { index, value ->
            val extent = width * value.toFloat() / total
            if (value > 0) {
                paint.color = ContextCompat.getColor(context, colors[index])
                canvas.drawRect(x, 0f, x + extent, height.toFloat(), paint)
            }
            x += extent
        }
        canvas.restore()
    }
}
