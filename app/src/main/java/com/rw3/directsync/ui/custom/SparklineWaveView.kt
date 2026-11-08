package com.rw3.directsync.ui.custom

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View
import androidx.core.content.ContextCompat
import com.rw3.directsync.R
import com.rw3.directsync.data.db.entity.HeartRateEntity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

/** Time-scaled HR chart. Missing intervals stay empty; touch a sample to inspect it. */
class SparklineWaveView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {
    private val d = resources.displayMetrics.density
    private val accent = ContextCompat.getColor(context, R.color.hr_chart_line)
    private val areaColor = ContextCompat.getColor(context, R.color.hr_chart_fill)
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = accent; strokeWidth = 2.3f * d; style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    private val grid = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.outline_subtle); strokeWidth = d; alpha = 160
    }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.on_surface_variant)
        textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 11f, resources.displayMetrics)
    }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = accent }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val averageLine = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FFE1DA"); strokeWidth = 1.3f * d; style = Paint.Style.STROKE
        pathEffect = DashPathEffect(floatArrayOf(6 * d, 4 * d), 0f)
    }
    private val plotBackground = Paint().apply { color = ContextCompat.getColor(context, R.color.surface_container_low) }
    private val markerFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#FFF0EC") }
    private val bubble = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.surface_container_highest)
    }
    private val time = SimpleDateFormat("HH:mm", Locale.getDefault())
    private val date = SimpleDateFormat("dd MMM", Locale.getDefault())
    private var records = emptyList<HeartRateEntity>()
    private var selected: HeartRateEntity? = null
    private var windowHours = 24
    private val refresh = object : Runnable {
        override fun run() { invalidate(); postDelayed(this, 60_000) }
    }
    init { isClickable = true; importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES }
    fun setWindowHours(hours: Int) { windowHours = hours; selected = null; invalidate() }
    fun setHeartRateRecords(values: List<HeartRateEntity>) {
        records = values.filter { it.bpm in 30..240 }.distinctBy { it.timestamp }.sortedBy { it.timestamp }
        selected = null
        contentDescription = if (records.isEmpty()) "Grafik detak jantung. Belum ada data."
        else "Grafik detak jantung $windowHours jam. ${records.size} sampel, minimum ${records.minOf { it.bpm }}, rata-rata ${records.map { it.bpm }.average().toInt()}, maksimum ${records.maxOf { it.bpm }} BPM."
        invalidate()
    }
    override fun onAttachedToWindow() { super.onAttachedToWindow(); post(refresh) }
    override fun onDetachedFromWindow() { removeCallbacks(refresh); super.onDetachedFromWindow() }
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val left = 36 * d
        val right = width - 12 * d
        val top = 34 * d
        val bottom = height - 30 * d
        if (right <= left || bottom <= top) return
        val now = System.currentTimeMillis()
        val duration = windowHours * 3_600_000L
        val start = now - duration
        val rawPoints = records.filter { it.timestamp in start..now }
        val bucketMillis = (if (windowHours == 24) 5 else 30) * 60_000L
        fun average(values: List<HeartRateEntity>) = values.groupBy { it.timestamp / bucketMillis }.values.map { bucket ->
            HeartRateEntity(timestamp = bucket.map { it.timestamp }.average().toLong(),
                bpm = bucket.map { it.bpm }.average().roundToInt())
        }
        val min = floor(((rawPoints.minOfOrNull { it.bpm } ?: 60) - 10) / 20.0).toInt() * 20
        val upperBound = ceil(((rawPoints.maxOfOrNull { it.bpm } ?: 110) + 10) / 20.0).toInt() * 20
        val tick = (ceil((upperBound - min) / 50.0).toInt() * 10).coerceAtLeast(10)
        val max = min + tick * 5
        fun x(t: Long) = left + ((t - start).toDouble() / duration * (right - left)).toFloat()
        fun y(bpm: Int) = bottom - (bpm - min).toFloat() / (max - min) * (bottom - top)
        canvas.drawRect(left, top, right, bottom, plotBackground)

        // Retain missing intervals. Area fill follows only recorded portions of the timeline.
        val rawSegments = mutableListOf<MutableList<HeartRateEntity>>()
        rawPoints.forEach { point ->
            if (rawSegments.isEmpty() || point.timestamp - rawSegments.last().last().timestamp > 20 * 60_000L) {
                rawSegments.add(mutableListOf())
            }
            rawSegments.last().add(point)
        }
        val segments = rawSegments.map { average(it) }
        val paths = segments.map { segment ->
            Path().apply {
                moveTo(x(segment.first().timestamp), y(segment.first().bpm))
                segment.drop(1).forEach { lineTo(x(it.timestamp), y(it.bpm)) }
            }
        }
        fill.shader = LinearGradient(0f, top, 0f, bottom,
            Color.argb(215, Color.red(areaColor), Color.green(areaColor), Color.blue(areaColor)),
            Color.argb(125, Color.red(areaColor), Color.green(areaColor), Color.blue(areaColor)), Shader.TileMode.CLAMP)
        canvas.save()
        canvas.clipRect(left, top, right, bottom)
        segments.forEachIndexed { index, segment ->
            if (segment.size > 1) {
                val area = Path(paths[index]).apply {
                    lineTo(x(segment.last().timestamp), bottom)
                    lineTo(x(segment.first().timestamp), bottom)
                    close()
                }
                canvas.drawPath(area, fill)
            }
        }
        // Grid is drawn over the fill, matching the reference chart's clear rectangular grid.
        repeat(6) { i -> canvas.drawLine(left, y(min + tick * i), right, y(min + tick * i), grid) }
        repeat(5) { i ->
            val xx = left + (right - left) * i / 4
            canvas.drawLine(xx, top, xx, bottom, grid)
        }
        val avg = rawPoints.takeIf { it.isNotEmpty() }?.map { it.bpm }?.average()?.toInt()
        avg?.let { canvas.drawLine(left, y(it), right, y(it), averageLine) }
        paths.forEach { canvas.drawPath(it, line) }
        canvas.restore()

        fun marker(point: HeartRateEntity, radius: Float = 4.5f * d) {
            canvas.drawCircle(x(point.timestamp), y(point.bpm), radius, markerFill)
            canvas.drawCircle(x(point.timestamp), y(point.bpm), radius, line)
            canvas.drawCircle(x(point.timestamp), y(point.bpm), 1.4f * d, dot)
        }
        segments.forEach { if (it.size == 1) marker(it.first(), 3.5f * d) }
        segments.firstOrNull()?.takeIf { it.size > 1 }?.first()?.let { marker(it) }
        segments.lastOrNull()?.takeIf { it.size > 1 }?.last()?.let { marker(it) }

        label.textAlign = Paint.Align.RIGHT
        repeat(6) { i ->
            val value = min + tick * i
            canvas.drawText("$value", left - 8 * d, y(value) + 4 * d, label)
        }
        label.textAlign = Paint.Align.LEFT
        canvas.drawText("BPM", left, 20 * d, label)
        repeat(5) { i ->
            val t = start + duration * i / 4
            label.textAlign = when (i) { 0 -> Paint.Align.LEFT; 4 -> Paint.Align.RIGHT; else -> Paint.Align.CENTER }
            canvas.drawText(if (i == 4) "Kini" else (if (windowHours > 24) date else time).format(Date(t)),
                left + (right - left) * i / 4, height - 8 * d, label)
        }
        if (rawPoints.isEmpty()) {
            label.textAlign = Paint.Align.CENTER
            canvas.drawText("Belum ada riwayat detak jantung", (left + right) / 2, (top + bottom) / 2, label)
            return
        }
        label.textAlign = Paint.Align.RIGHT
        if (selected == null) canvas.drawText("Rata-rata $avg BPM", right, 20 * d, label)
        selected?.takeIf { it.timestamp in start..now }?.let { point ->
            canvas.drawLine(x(point.timestamp), top, x(point.timestamp), bottom, grid)
            marker(point)
            val text = "${date.format(Date(point.timestamp))} ${time.format(Date(point.timestamp))}  ·  ${point.bpm} BPM"
            val boxWidth = label.measureText(text) + 16 * d
            canvas.drawRoundRect(right - boxWidth, 2 * d, right, 27 * d, 6 * d, 6 * d, bubble)
            canvas.drawText(text, right - 8 * d, 19 * d, label)
        }
    }
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN || event.actionMasked == MotionEvent.ACTION_MOVE) {
            val now = System.currentTimeMillis(); val duration = windowHours * 3_600_000L
            val target = now - duration + (((event.x - 36 * d) / (width - 48 * d)).coerceIn(0f, 1f) * duration).toLong()
            selected = records.filter { it.timestamp in (now - duration)..now }.minByOrNull { abs(it.timestamp - target) }
            invalidate(); return true
        }
        if (event.actionMasked == MotionEvent.ACTION_UP) { performClick(); return true }
        return super.onTouchEvent(event)
    }
    override fun performClick(): Boolean {
        super.performClick()
        selected?.let { announceForAccessibility("${date.format(Date(it.timestamp))} ${time.format(Date(it.timestamp))}, ${it.bpm} BPM") }
        return true
    }
}
