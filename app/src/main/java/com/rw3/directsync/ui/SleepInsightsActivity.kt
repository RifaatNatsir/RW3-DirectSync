package com.rw3.directsync.ui

import android.annotation.SuppressLint
import android.os.Bundle
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.rw3.directsync.R
import com.rw3.directsync.data.SleepInsights
import com.rw3.directsync.data.db.Rw3Database
import com.rw3.directsync.data.db.entity.SleepSessionEntity
import com.rw3.directsync.databinding.ActivitySleepInsightsBinding
import com.rw3.directsync.ui.custom.SleepTimelineView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

/** Local, reactive sleep exploration; does not acquire or alter the watch connection. */
class SleepInsightsActivity : AppCompatActivity() {
    companion object {
        const val EXTRA_SESSION_START = "sleep_session_start"
    }
    private lateinit var binding: ActivitySleepInsightsBinding
    private val dao by lazy { Rw3Database.getDatabase(applicationContext).sleepStageDao() }
    private val zone get() = ZoneId.systemDefault()
    private val locale = Locale("id", "ID")
    private var end = LocalDate.now()
    private var count = 7
    private var selectedDate = end
    private var selectedStart: Long? = null
    private var sessions = emptyList<SleepSessionEntity>()
    private var timelineSession: SleepSessionEntity? = null
    private var stageJob: Job? = null
    private var firstLoad = true
    private var jumpToTimeline = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        binding = ActivitySleepInsightsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        end = savedInstanceState?.getString("end")?.let(LocalDate::parse) ?: LocalDate.now(zone)
        count = savedInstanceState?.getInt("count", 7) ?: 7
        selectedDate = savedInstanceState?.getString("date")?.let(LocalDate::parse) ?: end
        selectedStart = (savedInstanceState?.getLong("session", -1)
            ?: intent.getLongExtra(EXTRA_SESSION_START, -1)).takeIf { it > 0 }
        jumpToTimeline = savedInstanceState == null && selectedStart != null
        binding.sleepPeriod.check(if (count == 7) R.id.sleepWeek else R.id.sleepMonth)
        binding.backToDashboard.setOnClickListener { finish() }
        binding.sleepPeriod.addOnButtonCheckedListener { _, id, checked ->
            if (checked) { count = if (id == R.id.sleepWeek) 7 else 30; render() }
        }
        binding.previousSleepPeriod.setOnClickListener { end = end.minusDays(count.toLong()); selectLatestInPeriod(); render() }
        binding.nextSleepPeriod.setOnClickListener { end = minOf(LocalDate.now(zone), end.plusDays(count.toLong())); selectLatestInPeriod(); render() }
        binding.sleepDurationChart.onDaySelected = { date ->
            selectedDate = date
            selectedStart = periodDays().first { it.date == date }.primary?.startTimestamp
            render()
        }
        binding.sleepTimeline.onSpanSelected = { span -> binding.selectedSleepPhase.text = describeSpan(span) }
        binding.toggleSleepPhaseDetails.setOnClickListener {
            val show = binding.sleepPhaseDetails.visibility != View.VISIBLE
            binding.sleepPhaseDetails.visibility = if (show) View.VISIBLE else View.GONE
            binding.toggleSleepPhaseDetails.text = if (show) "Sembunyikan rincian" else "Rincian fase"
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                dao.observeInsightSessions().collect { records ->
                    sessions = records
                    if (firstLoad) {
                        firstLoad = false
                        val requested = records.firstOrNull { it.startTimestamp == selectedStart }
                        if (requested != null) {
                            selectedDate = SleepInsights.date(requested.endTimestamp, zone)
                            if (selectedDate !in end.minusDays(count - 1L)..end) end = selectedDate
                        } else if (savedInstanceState == null) selectLatestInPeriod()
                    }
                    render()
                }
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("end", end.toString()); outState.putInt("count", count)
        outState.putString("date", selectedDate.toString()); outState.putLong("session", selectedStart ?: -1)
        super.onSaveInstanceState(outState)
    }

    private fun periodDays() = SleepInsights.days(sessions, end, count, zone)
    private fun selectLatestInPeriod() {
        val day = periodDays().lastOrNull { it.primary != null }
        selectedDate = day?.date ?: end
        selectedStart = day?.primary?.startTimestamp
    }
    private fun duration(minutes: Int) = "${minutes / 60}j ${minutes % 60}m"
    private fun date(value: LocalDate) = value.format(DateTimeFormatter.ofPattern("dd MMM yyyy", locale))
    private fun time(value: Long, pattern: String = "HH:mm") = Instant.ofEpochMilli(value).atZone(zone).format(DateTimeFormatter.ofPattern(pattern, locale))
    private fun clock(value: Double): String {
        val minute = value.roundToInt() % 1440
        return "%02d:%02d".format(locale, minute / 60, minute % 60)
    }

    @SuppressLint("SetTextI18n")
    private fun render() {
        val days = periodDays()
        if (selectedDate !in days.first().date..end) selectLatestInPeriod()
        binding.sleepDateRange.text = "${date(days.first().date)}\n– ${date(end)}"
        binding.previousSleepPeriod.isEnabled = sessions.any { SleepInsights.date(it.endTimestamp, zone) < days.first().date }
        binding.nextSleepPeriod.isEnabled = end < LocalDate.now(zone)
        val recorded = days.mapNotNull { it.minutes }
        binding.sleepAverage.text = if (recorded.isEmpty()) "Belum ada data" else duration(recorded.average().roundToInt())
        binding.sleepCoverage.text = if (recorded.isEmpty()) "Belum ada tidur tersimpan pada periode ini. Sinkronkan jam atau impor Mi Fitness dari Dashboard."
            else "Rata-rata per hari tercatat · ${recorded.size}/$count hari\n${days.sumOf { it.sessions.size }} sesi · total ${duration(recorded.sum())}"
        binding.sleepDurationChart.setDays(days, selectedDate)
        val chosenDay = days.first { it.date == selectedDate }
        binding.selectedSleepDay.text = "${date(selectedDate)} · " + (chosenDay.minutes?.let { "${duration(it)} · ${chosenDay.sessions.size} sesi" } ?: "belum ada data")
        val main = days.mapNotNull { it.primary }
        val bed = SleepInsights.clockStats(main.map { it.startTimestamp }, zone)
        val wake = SleepInsights.clockStats(main.map { it.endTimestamp }, zone)
        fun stat(view: TextView, title: String, value: SleepInsights.ClockStats?) {
            view.text = "$title\n${value?.let { clock(it.minute) } ?: "—"}\n" +
                if (value != null && value.count >= 2) "Variasi ${value.deviation.roundToInt()} menit" else "Variasi —"
        }
        stat(binding.sleepBedtimeStat, "Mulai tidur", bed)
        stat(binding.sleepWakeStat, "Bangun", wake)
        binding.sleepConsistencyStatus.text = when (main.size) {
            0 -> "Belum ada sesi untuk menghitung pola."
            1 -> "1 hari tercatat. Perlu minimal 2 hari untuk membandingkan jadwal."
            else -> "Berdasarkan ${main.size} hari tercatat; hari tanpa data dikecualikan."
        }
        binding.sleepTimingChart.setDays(days, zone)
        renderSessionPicker(days.flatMap { it.sessions }.sortedByDescending { it.endTimestamp })
        val selected = chosenDay.sessions.firstOrNull { it.startTimestamp == selectedStart }
        renderSession(selected)
    }

    private fun renderSessionPicker(options: List<SleepSessionEntity>) {
        val picker = binding.sleepSessionPicker
        picker.onItemSelectedListener = null
        val labels = listOf("Pilih sesi…") + options.map {
            "${time(it.endTimestamp, "dd MMM")} · ${time(it.startTimestamp)}–${time(it.endTimestamp)} · ${duration(it.totalMinutes)}"
        }
        picker.adapter = object : ArrayAdapter<String>(this, R.layout.item_sleep_session_picker, labels) {
            override fun areAllItemsEnabled() = false
            override fun isEnabled(position: Int) = position > 0
        }
        picker.setSelection(options.indexOfFirst { it.startTimestamp == selectedStart }.let { if (it < 0) 0 else it + 1 })
        picker.isEnabled = options.isNotEmpty()
        picker.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val session = options.getOrNull(position - 1) ?: return
                if (session.startTimestamp == selectedStart) return
                selectedStart = session.startTimestamp
                selectedDate = SleepInsights.date(session.endTimestamp, zone)
                render()
            }
        }
    }

    @SuppressLint("SetTextI18n")
    private fun renderSession(session: SleepSessionEntity?) {
        if (session == timelineSession && stageJob != null) return
        stageJob?.cancel(); stageJob = null; timelineSession = session
        binding.sleepTimeline.setSpans(emptyList())
        binding.sleepPhaseDetails.text = ""
        binding.sleepPhaseDetails.visibility = View.GONE
        binding.toggleSleepPhaseDetails.text = "Rincian fase"
        binding.toggleSleepPhaseDetails.isEnabled = false
        binding.selectedSleepPhase.text = ""
        binding.sleepSessionSummary.text = session?.let { duration(it.totalMinutes) } ?: "Belum ada sesi"
        binding.sleepSessionTimes.text = session?.let {
            "${time(it.startTimestamp, "dd MMM yyyy HH:mm")} – ${time(it.endTimestamp, "dd MMM yyyy HH:mm")}\nSumber: ${if (it.sourceFileId.startsWith("healthconnect:")) "Mi Fitness" else "smartwatch"}"
        } ?: "Pilih hari yang memiliki data atau gunakan pemilih sesi di atas."
        binding.sleepDeepTotal.text = "Deep\n${session?.takeIf { it.hasStageData }?.let { duration(it.deepMinutes) } ?: "—"}"
        binding.sleepLightTotal.text = "Light\n${session?.takeIf { it.hasStageData }?.let { duration(it.lightMinutes) } ?: "—"}"
        binding.sleepRemTotal.text = "REM\n${session?.takeIf { it.hasStageData }?.let { duration(it.remMinutes) } ?: "—"}"
        binding.sleepAwakeTotal.text = "Terjaga\n${session?.takeIf { it.hasStageData }?.let { duration(it.awakeMinutes) } ?: "—"}"
        binding.sleepTimelineStatus.text = if (session == null) "Belum ada timeline untuk hari ini." else "Membaca waktu fase…"
        if (session == null) return
        stageJob = lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                dao.observeSessionStages(session.sourceFileId, session.startTimestamp, session.endTimestamp).collect { records ->
                    val spans = withContext(Dispatchers.Default) { SleepInsights.timeline(session, records) }
                    binding.sleepTimeline.setSpans(spans)
                    val unknown = spans.filter { it.stage == SleepInsights.UNKNOWN }.sumOf { it.end - it.start }
                    val known = spans.any { it.stage != SleepInsights.UNKNOWN }
                    binding.sleepTimelineStatus.text = when {
                        !known -> "Waktu sesi tersedia; rincian fase belum tercatat. Area abu-abu tidak dianggap sebagai Deep, Light, REM, atau terjaga."
                        unknown > 0 -> "Urutan mengikuti waktu fase asli. Ada ${elapsed(unknown)} tanpa klasifikasi (abu-abu). Ringkasan fase dibulatkan ke menit."
                        else -> "Urutan mengikuti waktu fase asli. Ketuk blok berwarna untuk detail; ringkasan fase dibulatkan ke menit."
                    }
                    binding.selectedSleepPhase.text = if (known) "Ketuk fase untuk melihat waktu mulai dan selesai." else "Belum ada pergantian fase yang dapat ditampilkan."
                    binding.sleepPhaseDetails.text = spans.joinToString("\n\n") { describeSpan(it) }
                    binding.toggleSleepPhaseDetails.isEnabled = spans.isNotEmpty()
                    if (jumpToTimeline) {
                        jumpToTimeline = false
                        binding.sleepInsightsScroll.post { binding.sleepInsightsScroll.smoothScrollTo(0, binding.sleepTimelineCard.top) }
                    }
                }
            }
        }
    }

    private fun elapsed(millis: Long): String {
        val seconds = millis / 1000
        return if (seconds < 60) "$seconds detik" else "${seconds / 60} menit" + if (seconds % 60 > 0) " ${seconds % 60} detik" else ""
    }
    private fun describeSpan(span: SleepInsights.Span) = "${SleepTimelineView.stageLabel(span.stage)} · ${time(span.start, "dd MMM HH:mm:ss")} – ${time(span.end, "dd MMM HH:mm:ss")} · ${elapsed(span.end - span.start)}"
}
