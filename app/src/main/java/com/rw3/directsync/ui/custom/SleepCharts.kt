package com.rw3.directsync.ui.custom

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View
import androidx.core.content.ContextCompat
import com.rw3.directsync.R
import com.rw3.directsync.data.SleepInsights
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.ceil

abstract class SleepChart(context: Context, attrs: AttributeSet?) : View(context, attrs) {
    protected val ink = Paint(Paint.ANTI_ALIAS_FLAG)
    protected val density = resources.displayMetrics.density
    protected fun dp(value: Float) = value * density
    protected fun color(id: Int) = ContextCompat.getColor(context, id)
    protected fun label(canvas: Canvas, text: String, x: Float, y: Float, align: Paint.Align = Paint.Align.LEFT) {
        ink.shader = null; ink.color = color(R.color.on_surface_variant)
        ink.textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 11f, resources.displayMetrics); ink.textAlign = align
        canvas.drawText(text, x, y, ink)
    }
    protected fun grid(canvas: Canvas, x: Float, y: Float, right: Float) {
        ink.color = color(R.color.outline_subtle); ink.strokeWidth = dp(1f)
        canvas.drawLine(x, y, right, y, ink)
    }
}

class SleepDurationChartView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : SleepChart(context, attrs) {
    private var days = emptyList<SleepInsights.Day>()
    private var selected: LocalDate? = null
    var onDaySelected: ((LocalDate) -> Unit)? = null
    private val left get() = dp(34f)
    fun setDays(values: List<SleepInsights.Day>, selection: LocalDate) {
        days = values; selected = selection
        contentDescription = "Durasi tidur per tanggal bangun. " + values.joinToString("; ") {
            "${it.date}: ${it.minutes?.let { m -> "${m / 60} jam ${m % 60} menit" } ?: "belum ada data"}"
        }
        invalidate()
    }
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (days.isEmpty()) return
        val bottom = height - dp(27f); val top = dp(13f); val right = width - dp(8f)
        val maximum = maxOf(4, ceil((days.mapNotNull { it.minutes }.maxOrNull() ?: 0) / 120.0).toInt() * 2) * 60
        for (i in 0..2) {
            val y = bottom - (bottom - top) * i / 2
            grid(canvas, left, y, right)
            label(canvas, "${maximum * i / 120}j", left - dp(8f), y + dp(4f), Paint.Align.RIGHT)
        }
        val step = (right - left) / days.size
        days.forEachIndexed { i, day ->
            val x = left + step * (i + .5f)
            if (day.date == selected) {
                ink.color = color(R.color.surface_container_highest)
                canvas.drawRoundRect(x - step / 2, top, x + step / 2, bottom, dp(4f), dp(4f), ink)
            }
            val minutes = day.minutes
            if (minutes == null) {
                ink.color = color(R.color.on_surface_muted)
                canvas.drawCircle(x, bottom - dp(3f), dp(1.5f), ink)
            } else {
                val y = bottom - (bottom - top) * minutes / maximum
                ink.shader = LinearGradient(0f, y, 0f, bottom, color(R.color.sleep_light), color(R.color.sleep_deep), Shader.TileMode.CLAMP)
                val half = (step * .32f).coerceAtMost(dp(14f))
                canvas.drawRoundRect(x - half, y, x + half, bottom, dp(3f), dp(3f), ink)
                ink.shader = null
            }
            if (i == 0 || i == days.lastIndex || days.size <= 7 || (i % 7 == 0 && days.lastIndex - i >= 4))
                label(canvas, day.date.format(DateTimeFormatter.ofPattern("dd/MM")), x, height - dp(6f), Paint.Align.CENTER)
        }
    }
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_UP && days.isNotEmpty() && event.x >= left && event.x <= width - dp(8f)) {
            val index = ((event.x - left) / (width - dp(8f) - left) * days.size).toInt().coerceIn(days.indices)
            onDaySelected?.invoke(days[index].date); performClick()
        }
        return true
    }
    override fun performClick(): Boolean { super.performClick(); return true }
}

class SleepTimingChartView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : SleepChart(context, attrs) {
    private data class Point(val date: LocalDate, val bed: Double?, val wake: Double?)
    private var points = emptyList<Point>()
    private var low = 1080.0
    private var high = 1920.0
    fun setDays(days: List<SleepInsights.Day>, zone: ZoneId) {
        val sessions = days.mapNotNull { it.primary }
        val center = SleepInsights.clockStats(sessions.map { it.startTimestamp }, zone)?.minute ?: 1440.0
        fun clock(time: Long) = Instant.ofEpochMilli(time).atZone(zone).toLocalTime().toSecondOfDay() / 60.0
        points = days.map { day ->
            val s = day.primary
            if (s == null) Point(day.date, null, null) else {
                var bed = clock(s.startTimestamp)
                while (bed < center - 720) bed += 1440
                while (bed > center + 720) bed -= 1440
                val expectedWake = bed + (s.endTimestamp - s.startTimestamp) / 60_000.0
                var wake = clock(s.endTimestamp)
                while (wake < expectedWake - 720) wake += 1440
                while (wake > expectedWake + 720) wake -= 1440
                Point(day.date, bed, wake)
            }
        }
        val times = points.flatMap { listOfNotNull(it.bed, it.wake) }
        low = kotlin.math.floor((times.minOrNull() ?: 1080.0) / 120) * 120
        high = maxOf(low + 240, ceil((times.maxOrNull() ?: 1920.0) / 120) * 120)
        contentDescription = "Pola jam tidur dan bangun untuk sesi terlama setiap tanggal. " + days.mapNotNull { it.primary }.joinToString("; ") {
            val f = DateTimeFormatter.ofPattern("dd MMM HH:mm")
            "${Instant.ofEpochMilli(it.startTimestamp).atZone(zone).format(f)} sampai ${Instant.ofEpochMilli(it.endTimestamp).atZone(zone).format(f)}"
        }
        invalidate()
    }
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (points.isEmpty()) return
        val left = dp(47f); val right = width - dp(10f); val top = dp(12f); val bottom = height - dp(24f)
        fun y(value: Double) = top + ((value - low) / (high - low)).toFloat() * (bottom - top)
        for (i in 0..3) {
            val minute = low + (high - low) * i / 3
            val tick = ((minute.toInt() % 1440) + 1440) % 1440
            grid(canvas, left, y(minute), right)
            label(canvas, "%02d:%02d".format(tick / 60, tick % 60), left - dp(5f), y(minute) + dp(4f), Paint.Align.RIGHT)
        }
        val step = (right - left) / points.size
        listOf(false, true).forEach { wake ->
            points.forEachIndexed { i, point ->
                val time = (if (wake) point.wake else point.bed) ?: return@forEachIndexed
                val x = left + step * (i + .5f)
                ink.color = color(if (wake) R.color.primary_mint else R.color.sleep_rem)
                ink.strokeWidth = dp(1.8f)
                val previous = points.getOrNull(i - 1)?.let { if (wake) it.wake else it.bed }
                if (previous != null) canvas.drawLine(x - step, y(previous), x, y(time), ink)
                canvas.drawCircle(x, y(time), dp(if (points.size > 7) 2.5f else 4f), ink)
            }
        }
        points.forEachIndexed { i, point ->
            if (i == 0 || i == points.lastIndex || points.size <= 7 || (i % 7 == 0 && points.lastIndex - i >= 4))
                label(canvas, point.date.format(DateTimeFormatter.ofPattern("dd/MM")), left + step * (i + .5f), height - dp(5f), Paint.Align.CENTER)
        }
    }
}

class SleepTimelineView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : SleepChart(context, attrs) {
    private var spans = emptyList<SleepInsights.Span>()
    private var selected: SleepInsights.Span? = null
    var onSpanSelected: ((SleepInsights.Span) -> Unit)? = null
    private val left get() = dp(78f)
    fun setSpans(values: List<SleepInsights.Span>) {
        spans = values; selected = null
        contentDescription = if (values.isEmpty()) "Belum ada timeline tidur" else "Timeline fase tidur. Ketuk blok untuk waktu fase. Rincian teks tersedia di tombol Rincian fase."
        invalidate()
    }
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val right = width - dp(10f); val top = dp(6f); val bottom = height - dp(33f)
        val row = (bottom - top) / 5
        SleepInsights.stages.forEachIndexed { i, stage ->
            val y = top + row * i
            label(canvas, stageLabel(stage), left - dp(9f), y + row / 2 + dp(4f), Paint.Align.RIGHT)
            ink.color = color(R.color.surface_container_low)
            canvas.drawRoundRect(left, y + dp(4f), right, y + row - dp(4f), dp(4f), dp(4f), ink)
        }
        val start = spans.firstOrNull()?.start ?: return
        val end = spans.last().end
        fun x(time: Long) = left + (right - left) * ((time - start).toDouble() / (end - start)).toFloat()
        spans.forEach { span ->
            val y = top + row * SleepInsights.stages.indexOf(span.stage)
            ink.color = color(stageColor(span.stage))
            canvas.drawRoundRect(RectF(x(span.start), y + dp(6f), maxOf(x(span.end), x(span.start) + dp(1f)), y + row - dp(6f)), dp(3f), dp(3f), ink)
        }
        selected?.let {
            ink.color = color(R.color.on_surface); ink.strokeWidth = dp(1f)
            val mid = x(it.start + (it.end - it.start) / 2)
            canvas.drawLine(mid, top, mid, bottom, ink)
        }
        val f = DateTimeFormatter.ofPattern("HH:mm")
        for (i in 0..2) {
            val time = start + (end - start) * i / 2
            label(canvas, Instant.ofEpochMilli(time).atZone(ZoneId.systemDefault()).format(f), x(time), height - dp(10f),
                if (i == 0) Paint.Align.LEFT else if (i == 2) Paint.Align.RIGHT else Paint.Align.CENTER)
        }
    }
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_UP && spans.isNotEmpty() && event.x >= left && event.x <= width - dp(10f)) {
            val time = spans.first().start + ((event.x - left) / (width - dp(10f) - left) * (spans.last().end - spans.first().start)).toLong()
            selected = spans.firstOrNull { time >= it.start && time < it.end } ?: spans.last()
            selected?.let { onSpanSelected?.invoke(it) }; invalidate(); performClick()
        }
        return true
    }
    override fun performClick(): Boolean { super.performClick(); return true }

    companion object {
        fun stageLabel(stage: String) = when (stage) { "DEEP" -> "Deep"; "LIGHT" -> "Light"; "REM" -> "REM"; "AWAKE" -> "Terjaga"; else -> "Tanpa fase" }
        fun stageColor(stage: String) = when (stage) { "DEEP" -> R.color.sleep_deep; "LIGHT" -> R.color.sleep_light; "REM" -> R.color.sleep_rem; "AWAKE" -> R.color.sleep_awake; else -> R.color.on_surface_muted }
    }
}
