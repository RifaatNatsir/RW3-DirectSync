package com.rw3.directsync.ui

import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.os.IBinder
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.rw3.directsync.R
import com.rw3.directsync.ble.BleSyncService
import com.rw3.directsync.data.db.Rw3Database
import com.rw3.directsync.data.db.entity.ActivityRecordEntity
import com.rw3.directsync.data.db.entity.HeartRateEntity
import com.rw3.directsync.databinding.ActivityHeartRateDetailBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Screen 07 · Detail Detak Jantung
 * Displays continuous 24h / 7d heart rate readings, resting heart rate (RHR),
 * zone breakdown estimated from real recorded samples, recent logs, live measurement trigger,
 * and CSV export backed directly by Room DB & BleSyncService.
 */
class HeartRateDetailActivity : AppCompatActivity() {

    private lateinit var binding: ActivityHeartRateDetailBinding
    private val db by lazy { Rw3Database.getDatabase(applicationContext) }
    private val locale = Locale("id", "ID")
    private val clockFmt = SimpleDateFormat("HH:mm", Locale.getDefault())
    private val dateClockFmt = SimpleDateFormat("dd MMM HH:mm", Locale("id", "ID"))

    private var windowHours = 24
    private var currentRecords: List<HeartRateEntity> = emptyList()
    private var currentWorkouts: List<ActivityRecordEntity> = emptyList()
    private var latestDailyRhr: Int? = null
    private var windowJob: Job? = null

    private var bleService: BleSyncService? = null
    private var serviceObsJob: Job? = null

    private val csvExportLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("text/csv")
    ) { uri: Uri? ->
        if (uri != null) exportRecordsToCsv(uri)
    }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as? BleSyncService.LocalBinder ?: return
            val svc = binder.getService()
            bleService = svc
            observeBleState(svc)
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            bleService = null
            serviceObsJob?.cancel()
            updateMeasureButton(isStreaming = false)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        UiTransitions.registerCloseTransitionIfSupported(this)
        binding = ActivityHeartRateDetailBinding.inflate(layoutInflater)
        setContentView(binding.root)

        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                UiTransitions.finishWithCloseTransition(this@HeartRateDetailActivity)
            }
        })

        binding.btnBackHr.setOnClickListener {
            UiTransitions.finishWithCloseTransition(this)
        }

        binding.btnWindow24h.setOnClickListener { switchWindow(24) }
        binding.btnWindow7d.setOnClickListener { switchWindow(168) }

        binding.btnMeasureHr.setOnClickListener {
            val svc = bleService
            if (svc != null) {
                val streaming = svc.toggleHeartRateStream()
                updateMeasureButton(streaming)
                val msg = if (streaming) {
                    "Sensor detak jantung diaktifkan..."
                } else {
                    "Pengukuran live dihentikan"
                }
                Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
            } else {
                val intent = Intent(this, BleSyncService::class.java).apply {
                    action = BleSyncService.ACTION_TOGGLE_HEART_RATE
                }
                ContextCompat.startForegroundService(this, intent)
                Toast.makeText(this, "Meminta pengukuran detak jantung...", Toast.LENGTH_SHORT).show()
            }
        }

        binding.btnExportHrCsv.setOnClickListener {
            if (currentRecords.isEmpty()) {
                Toast.makeText(this, "Belum ada sampel HR untuk diekspor.", Toast.LENGTH_SHORT).show()
            } else {
                val tag = if (windowHours == 24) "24h" else "7d"
                val stamp = SimpleDateFormat("yyyyMMdd_HHmm", Locale.US).format(Date())
                csvExportLauncher.launch("rw3_heart_rate_${tag}_$stamp.csv")
            }
        }

        observeStaticMetrics()
        switchWindow(24)
    }

    override fun onStart() {
        super.onStart()
        Intent(this, BleSyncService::class.java).also { intent ->
            bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
        }
    }

    override fun onStop() {
        super.onStop()
        serviceObsJob?.cancel()
        if (bleService != null) {
            unbindService(serviceConnection)
            bleService = null
        }
    }

    private fun observeBleState(svc: BleSyncService) {
        serviceObsJob?.cancel()
        serviceObsJob = lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    svc.isHrStreaming.collect { streaming ->
                        updateMeasureButton(streaming)
                    }
                }
                launch {
                    svc.heartRateBpm.collect { liveBpm ->
                        if (liveBpm != null && svc.isHrStreaming.value) {
                            renderHeroReadout(liveBpm, System.currentTimeMillis(), isLive = true)
                        }
                    }
                }
            }
        }
    }

    private fun updateMeasureButton(isStreaming: Boolean) {
        if (isStreaming) {
            binding.btnMeasureHr.text = "Hentikan"
            binding.btnMeasureHr.setBackgroundResource(R.drawable.bg_pill_mint)
            binding.btnMeasureHr.setTextColor(ContextCompat.getColor(this, R.color.primary_mint))
        } else {
            binding.btnMeasureHr.text = "Ukur Ulang"
            binding.btnMeasureHr.setBackgroundResource(R.drawable.bg_pill_coral)
            binding.btnMeasureHr.setTextColor(ContextCompat.getColor(this, R.color.heart_rate_coral))
        }
    }

    private fun observeStaticMetrics() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    db.activityRecordDao().observeDaily().collect { daily ->
                        latestDailyRhr = daily?.restingHeartRate
                        binding.tvHrRhrValue.text = latestDailyRhr?.let { "$it BPM" } ?: "— BPM"
                    }
                }
                launch {
                    db.activityRecordDao().observeWorkouts().collect { workouts ->
                        currentWorkouts = workouts
                        if (currentRecords.isNotEmpty()) renderRecentLogs()
                    }
                }
            }
        }
    }

    @SuppressLint("SetTextI18n")
    private fun switchWindow(hours: Int) {
        windowHours = hours
        val is24h = hours == 24
        binding.btnWindow24h.setBackgroundResource(if (is24h) R.drawable.bg_pill_coral else R.drawable.bg_pill_surface)
        binding.btnWindow24h.setTextColor(
            ContextCompat.getColor(this, if (is24h) R.color.heart_rate_coral else R.color.on_surface_variant)
        )
        binding.btnWindow7d.setBackgroundResource(if (!is24h) R.drawable.bg_pill_coral else R.drawable.bg_pill_surface)
        binding.btnWindow7d.setTextColor(
            ContextCompat.getColor(this, if (!is24h) R.color.heart_rate_coral else R.color.on_surface_variant)
        )
        binding.tvHrChartTitle.text = if (is24h) "Grafik Detak 24 Jam" else "Grafik Detak 7 Hari"
        binding.tvHrDetailSubtitle.text = if (is24h) "Pemantauan kontinu · 24 jam" else "Pemantauan kontinu · 7 hari"
        binding.hrDetailSparkline.setWindowHours(hours)

        windowJob?.cancel()
        windowJob = lifecycleScope.launch {
            val cutoff = System.currentTimeMillis() - hours * 3_600_000L
            db.heartRateDao().observeRecordsSince(cutoff).collect { raw ->
                val clean = raw
                    .filter { it.bpm in 30..240 && it.timestamp <= System.currentTimeMillis() }
                    .distinctBy { it.timestamp }
                    .sortedBy { it.timestamp }
                currentRecords = clean
                renderAll(clean)
            }
        }
    }

    @SuppressLint("SetTextI18n")
    private fun renderAll(records: List<HeartRateEntity>) {
        val liveActive = bleService?.isHrStreaming?.value == true
        val liveBpm = bleService?.heartRateBpm?.value
        val latest = records.lastOrNull()

        if (liveActive && liveBpm != null) {
            renderHeroReadout(liveBpm, System.currentTimeMillis(), isLive = true)
        } else if (latest != null) {
            renderHeroReadout(latest.bpm, latest.timestamp, isLive = false)
        } else {
            binding.tvHrHeroKicker.text = "DETAK SAAT INI · BELUM ADA DATA"
            binding.tvHrHeroBpm.text = "--"
            binding.tvHrHeroBadge.text = "Menunggu Data"
            binding.tvHrHeroBadge.setBackgroundResource(R.drawable.bg_pill_surface)
            binding.tvHrHeroBadge.setTextColor(ContextCompat.getColor(this, R.color.on_surface_variant))
        }

        if (records.isEmpty()) {
            binding.tvHrStatMin.text = "-- BPM"
            binding.tvHrStatAvg.text = "-- BPM"
            binding.tvHrStatMax.text = "-- BPM"
            binding.tvHrChartEmpty.visibility = View.VISIBLE
            binding.hrDetailSparkline.setHeartRateRecords(emptyList())
            binding.tvHrChartRangeLabel.text = "Sinkronkan jam atau mulai sensor optik untuk melihat grafik."
        } else {
            val minBpm = records.minOf { it.bpm }
            val maxBpm = records.maxOf { it.bpm }
            val avgBpm = records.map { it.bpm }.average().roundToInt()
            binding.tvHrStatMin.text = "$minBpm BPM"
            binding.tvHrStatAvg.text = "$avgBpm BPM"
            binding.tvHrStatMax.text = "$maxBpm BPM"
            binding.tvHrChartEmpty.visibility = View.GONE
            binding.hrDetailSparkline.setHeartRateRecords(records)
            val spanStart = if (windowHours == 24) clockFmt.format(Date(records.first().timestamp)) else dateClockFmt.format(Date(records.first().timestamp))
            val spanEnd = clockFmt.format(Date(records.last().timestamp))
            binding.tvHrChartRangeLabel.text = "${records.size} sampel · $spanStart – $spanEnd · ketuk untuk detail"
        }

        renderZones(records)
        renderRecentLogs()
    }

    @SuppressLint("SetTextI18n")
    private fun renderHeroReadout(bpm: Int, timestampMs: Long, isLive: Boolean) {
        val elapsedMin = ((System.currentTimeMillis() - timestampMs) / 60_000L).coerceAtLeast(0L)
        val recency = when {
            isLive -> "STREAM LIVE"
            elapsedMin < 1L -> "BARU SAJA"
            elapsedMin < 60L -> "${elapsedMin}M LALU"
            else -> clockFmt.format(Date(timestampMs))
        }
        binding.tvHrHeroKicker.text = "DETAK SAAT INI · $recency"
        binding.tvHrHeroBpm.text = bpm.toString()

        when {
            bpm < 60 -> {
                binding.tvHrHeroBadge.text = "Relaksasi · Rendah"
                binding.tvHrHeroBadge.setBackgroundResource(R.drawable.bg_pill_cyan)
                binding.tvHrHeroBadge.setTextColor(ContextCompat.getColor(this, R.color.secondary_cyan))
            }
            bpm <= 103 -> {
                binding.tvHrHeroBadge.text = "Normal · Ritme Stabil"
                binding.tvHrHeroBadge.setBackgroundResource(R.drawable.bg_pill_mint)
                binding.tvHrHeroBadge.setTextColor(ContextCompat.getColor(this, R.color.primary_mint))
            }
            bpm <= 145 -> {
                binding.tvHrHeroBadge.text = "Aktif · Aerobik"
                binding.tvHrHeroBadge.setBackgroundResource(R.drawable.bg_pill_amber)
                binding.tvHrHeroBadge.setTextColor(ContextCompat.getColor(this, R.color.sleep_awake))
            }
            else -> {
                binding.tvHrHeroBadge.text = "Intensitas Tinggi"
                binding.tvHrHeroBadge.setBackgroundResource(R.drawable.bg_pill_coral)
                binding.tvHrHeroBadge.setTextColor(ContextCompat.getColor(this, R.color.heart_rate_coral))
            }
        }
    }

    @SuppressLint("SetTextI18n")
    private fun renderZones(records: List<HeartRateEntity>) {
        var warmupSec = 0L
        var fatSec = 0L
        var aerobicSec = 0L
        var anaerobicSec = 0L

        if (records.size == 1) {
            when {
                records[0].bpm < 104 -> warmupSec = 60L
                records[0].bpm <= 124 -> fatSec = 60L
                records[0].bpm <= 145 -> aerobicSec = 60L
                else -> anaerobicSec = 60L
            }
        } else if (records.size >= 2) {
            for (i in 0 until records.size) {
                val cur = records[i]
                val dtSec = if (i < records.lastIndex) {
                    val rawDiffSec = (records[i + 1].timestamp - cur.timestamp) / 1000L
                    rawDiffSec.coerceIn(1L, 120L)
                } else {
                    60L
                }
                when {
                    cur.bpm < 104 -> warmupSec += dtSec
                    cur.bpm <= 124 -> fatSec += dtSec
                    cur.bpm <= 145 -> aerobicSec += dtSec
                    else -> anaerobicSec += dtSec
                }
            }
        }

        val totalSec = warmupSec + fatSec + aerobicSec + anaerobicSec
        val totalMin = (totalSec / 60L)
        binding.tvHrZonesTotal.text = if (records.isEmpty()) "Belum ada sampel" else "${records.size} sampel · ~$totalMin mnt"

        val maxSec = maxOf(warmupSec, fatSec, aerobicSec, anaerobicSec, 1L)
        applyZoneRow(binding.trackZoneAerobic, binding.barZoneAerobic, binding.tvZoneAerobicTime, aerobicSec, maxSec)
        applyZoneRow(binding.trackZoneFat, binding.barZoneFat, binding.tvZoneFatTime, fatSec, maxSec)
        applyZoneRow(binding.trackZoneWarmup, binding.barZoneWarmup, binding.tvZoneWarmupTime, warmupSec, maxSec)
        applyZoneRow(binding.trackZoneAnaerobic, binding.barZoneAnaerobic, binding.tvZoneAnaerobicTime, anaerobicSec, maxSec)
    }

    private fun applyZoneRow(
        track: View,
        bar: View,
        label: TextView,
        seconds: Long,
        maxSeconds: Long
    ) {
        val minutes = seconds / 60L
        label.text = if (seconds == 0L) "0 mnt" else if (minutes < 1L) "<1 mnt" else "$minutes mnt"
        track.post {
            val w = track.width
            if (w <= 0) return@post
            val fraction = if (seconds <= 0L) 0f else (seconds.toFloat() / maxSeconds).coerceIn(0.04f, 1f)
            val lp = bar.layoutParams
            lp.width = (w * fraction).roundToInt()
            bar.layoutParams = lp
        }
    }

    @SuppressLint("SetTextI18n")
    private fun renderRecentLogs() {
        val container = binding.layoutHrLogsList
        container.removeAllViews()
        val recent = currentRecords.asReversed().take(15)
        if (recent.isEmpty()) {
            container.addView(TextView(this).apply {
                text = "Belum ada riwayat pengukuran di rentang waktu ini."
                textSize = 13f
                setTextColor(ContextCompat.getColor(this@HeartRateDetailActivity, R.color.on_surface_muted))
            })
            return
        }

        val density = resources.displayMetrics.density
        val padH = (12 * density).roundToInt()
        val padV = (10 * density).roundToInt()
        val marginB = (6 * density).roundToInt()

        recent.forEach { sample ->
            val matchedWorkout = currentWorkouts.firstOrNull { w ->
                val end = w.endTimestamp ?: (w.timestamp + (w.activeSeconds ?: 0) * 1000L)
                sample.timestamp in w.timestamp..end
            }
            val contextLabel = when {
                matchedWorkout != null -> com.rw3.directsync.data.SportTypeCatalog.displayName(matchedWorkout.sportType)
                sample.bpm < 75 -> "Kondisi Istirahat"
                sample.bpm <= 103 -> "Aktivitas Ringan"
                sample.bpm <= 145 -> "Aktivitas Aerobik"
                else -> "Intensitas Tinggi"
            }

            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setBackgroundResource(R.drawable.bg_box_dark)
                setPadding(padH, padV, padH, padV)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = marginB }
            }

            val timeText = if (windowHours == 24) clockFmt.format(Date(sample.timestamp)) else dateClockFmt.format(Date(sample.timestamp))
            val tvLeft = TextView(this).apply {
                text = "$timeText · $contextLabel"
                textSize = 12f
                setTextColor(ContextCompat.getColor(this@HeartRateDetailActivity, R.color.on_surface_variant))
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }

            val valColor = ContextCompat.getColor(
                this,
                when {
                    sample.bpm >= 146 -> R.color.hr_chart_line
                    sample.bpm >= 104 -> R.color.secondary_cyan
                    else -> R.color.primary_mint
                }
            )
            val tvRight = TextView(this).apply {
                text = "${sample.bpm} BPM"
                textSize = 12f
                setTypeface(null, Typeface.BOLD)
                setTextColor(valColor)
            }

            row.addView(tvLeft)
            row.addView(tvRight)
            container.addView(row)
        }
    }

    private fun exportRecordsToCsv(uri: Uri) {
        val snapshot = currentRecords.toList()
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val isoFmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US)
                val sb = StringBuilder("timestamp_ms,local_time,bpm\n")
                snapshot.forEach { r ->
                    sb.append(r.timestamp).append(',')
                        .append(isoFmt.format(Date(r.timestamp))).append(',')
                        .append(r.bpm).append('\n')
                }
                contentResolver.openOutputStream(uri)?.use { out ->
                    out.write(sb.toString().toByteArray(Charsets.UTF_8))
                }
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@HeartRateDetailActivity, "${snapshot.size} sampel HR disimpan ke CSV.", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@HeartRateDetailActivity, "Gagal mengekspor CSV: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }
}
