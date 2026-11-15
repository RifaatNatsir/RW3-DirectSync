package com.rw3.directsync.ui

import android.annotation.SuppressLint
import android.net.Uri
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.rw3.directsync.R
import com.rw3.directsync.data.MiFitnessHealthMapper
import com.rw3.directsync.data.SportTypeCatalog
import com.rw3.directsync.data.db.Rw3Database
import com.rw3.directsync.data.db.entity.ActivityRecordEntity
import com.rw3.directsync.data.db.entity.HeartRateEntity
import com.rw3.directsync.databinding.ActivityWorkoutDetailBinding
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory.*
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point

/**
 * Screen 08 · Detail Sesi Olahraga
 * Displays workout session metrics stored in Room DB alongside real heart rate
 * samples captured during [timestamp, endTimestamp]. Strictly avoids fabricating
 * GPS routes, per-km pace splits, cadence, or stride length when unavailable.
 */
class WorkoutDetailActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_WORKOUT_ID = "extra_workout_id"
    }

    private lateinit var binding: ActivityWorkoutDetailBinding
    private val db by lazy { Rw3Database.getDatabase(applicationContext) }
    private val numberFmt = NumberFormat.getIntegerInstance(Locale("id", "ID"))
    private val dateTimeFmt = SimpleDateFormat("dd/MM/yyyy · HH:mm", Locale("id", "ID"))

    private var currentRecord: ActivityRecordEntity? = null
    private var sessionHrSamples: List<HeartRateEntity> = emptyList()
    private var routeMap: MapLibreMap? = null
    private var currentRoute: List<MiFitnessHealthMapper.RoutePoint> = emptyList()
    private var routeStyleLoaded = false
    private var routeMapRendered = false

    private val exportSummaryLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain")
    ) { uri: Uri? ->
        if (uri != null) exportWorkoutSummary(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        MapLibre.getInstance(applicationContext)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        UiTransitions.registerCloseTransitionIfSupported(this)
        binding = ActivityWorkoutDetailBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.workoutMapView.onCreate(savedInstanceState)
        setupRouteMap()

        val baseTopPad = binding.workoutDetailRoot.paddingTop
        val baseBottomPad = binding.workoutDetailRoot.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(binding.workoutDetailRoot) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            view.setPadding(bars.left, baseTopPad + bars.top, bars.right, baseBottomPad + bars.bottom)
            insets
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                UiTransitions.finishWithCloseTransition(this@WorkoutDetailActivity)
            }
        })

        binding.btnBackWorkout.setOnClickListener {
            UiTransitions.finishWithCloseTransition(this)
        }

        binding.btnExportWorkout.setOnClickListener {
            val rec = currentRecord
            if (rec == null) {
                Toast.makeText(this, "Data sesi belum siap diekspor.", Toast.LENGTH_SHORT).show()
            } else {
                val stamp = SimpleDateFormat("yyyyMMdd_HHmm", Locale.US).format(Date(rec.timestamp))
                exportSummaryLauncher.launch("rw3_workout_$stamp.txt")
            }
        }

        val workoutId = intent.getStringExtra(EXTRA_WORKOUT_ID)
        if (workoutId.isNullOrBlank()) {
            Toast.makeText(this, "ID sesi olahraga tidak ditemukan.", Toast.LENGTH_SHORT).show()
            UiTransitions.finishWithCloseTransition(this)
            return
        }

        loadWorkout(workoutId)
    }

    private fun loadWorkout(workoutId: String) {
        lifecycleScope.launch {
            val record = withContext(Dispatchers.IO) {
                db.activityRecordDao().find(workoutId)
            }
            if (record == null) {
                Toast.makeText(this@WorkoutDetailActivity, "Sesi olahraga tidak ditemukan.", Toast.LENGTH_SHORT).show()
                UiTransitions.finishWithCloseTransition(this@WorkoutDetailActivity)
                return@launch
            }
            currentRecord = record
            val durationSec = resolveDurationSeconds(record)
            val endMs = record.endTimestamp ?: (record.timestamp + durationSec * 1000L)
            val hrSamples = withContext(Dispatchers.IO) {
                db.heartRateDao().getRecordsBetween(record.timestamp, endMs)
                    .filter { it.bpm in 30..240 }
                    .distinctBy { it.timestamp }
            }
            sessionHrSamples = hrSamples
            bindWorkout(record, durationSec, hrSamples)
        }
    }

    private fun resolveDurationSeconds(record: ActivityRecordEntity): Int {
        val active = record.activeSeconds
        if (active != null && active > 0) return active
        val end = record.endTimestamp
        if (end != null && end > record.timestamp) {
            return ((end - record.timestamp) / 1000L).toInt()
        }
        return 0
    }

    @SuppressLint("SetTextI18n")
    private fun bindWorkout(
        record: ActivityRecordEntity,
        durationSec: Int,
        samples: List<HeartRateEntity>
    ) {
        val sportTitle = SportTypeCatalog.displayName(record.sportType)
        val sourceLabel = if (record.sourceFileId.startsWith(MiFitnessHealthMapper.HC_SOURCE_PREFIX)) {
            "Mi Fitness"
        } else {
            "RW3"
        }
        binding.tvWorkoutDetailCategory.text = "$sourceLabel · $sportTitle"
        binding.imgWorkoutHeroIcon.setImageResource(SportTypeCatalog.iconRes(record.sportType))
        binding.tvWorkoutDetailDate.text = dateTimeFmt.format(Date(record.timestamp))

        val routePoints = MiFitnessHealthMapper.decodeRoute(record.routeJson).filter {
            it.lat.isFinite() && it.lon.isFinite() && it.lat in -90.0..90.0 && it.lon in -180.0..180.0
        }
        currentRoute = routePoints
        binding.workoutRouteView.setRoute(routePoints)
        binding.workoutMapView.visibility = if (routePoints.size >= 2) View.VISIBLE else View.GONE
        binding.btnFitWorkoutRoute.visibility = if (routePoints.size >= 2) View.VISIBLE else View.GONE
        loadRouteMap()
        binding.workoutNoRouteBanner.visibility = if (routePoints.size >= 2) View.GONE else View.VISIBLE
        if (routePoints.size < 2) {
            val missingRoutePerm = android.os.Build.VERSION.SDK_INT >= 34 &&
                !com.rw3.directsync.data.MiFitnessHealthConnectImporter.hasPermission(
                    this,
                    com.rw3.directsync.data.MiFitnessHealthConnectImporter.READ_EXERCISE_ROUTES
                )
            binding.tvWorkoutRouteEmptySubtitle.text = when {
                missingRoutePerm && record.distanceMeters != null ->
                    "Aktifkan 'Rute olahraga' di Atur izin Health Connect lalu impor ulang"
                record.sourceFileId.startsWith(MiFitnessHealthMapper.HC_SOURCE_PREFIX) ->
                    "Sesi olahraga ini tidak memiliki data rute GPS di Health Connect"
                else ->
                    "File aktivitas BLE Redmi Watch 3 hanya memuat ringkasan sesi"
            }
        }

        val formattedHms = formatHms(durationSec)
        val mins = durationSec / 60
        val distMeters = record.distanceMeters?.takeIf { it >= 10.0 }
        if (distMeters != null) {
            val km = distMeters / 1000.0
            binding.tvWorkoutHeroDuration.text = if (km >= 10.0) {
                String.format(Locale.US, "%.1f", km)
            } else {
                String.format(Locale.US, "%.2f", km).trimEnd('0').trimEnd('.')
            }
            binding.tvWorkoutHeroUnit.text = "km"
        } else {
            binding.tvWorkoutHeroDuration.text = if (durationSec > 0) formattedHms else "—"
            binding.tvWorkoutHeroUnit.text = if (durationSec > 0) "($mins mnt)" else "durasi"
        }

        binding.tvWorkoutStatDuration.text = if (durationSec > 0) formattedHms else "—"
        binding.tvWorkoutStatCalories.text = record.calories?.let { "${numberFmt.format(it)} kcal" } ?: "—"
        binding.tvWorkoutStatBurnRate.text = if (record.calories != null && durationSec >= 60) {
            val rate = record.calories.toFloat() / (durationSec / 60f)
            String.format(Locale.US, "%.1f", rate)
        } else {
            "—"
        }

        val sampleBpm = samples.map { it.bpm }
        val avgHr = record.averageHeartRate ?: sampleBpm.takeIf { it.isNotEmpty() }?.average()?.roundToInt()
        val minHr = record.minHeartRate ?: sampleBpm.minOrNull()
        val maxHr = record.maxHeartRate ?: sampleBpm.maxOrNull()

        binding.tvWorkoutStatHrAvg.text = avgHr?.let { "$it BPM" } ?: "—"
        binding.tvWorkoutStatHrMin.text = minHr?.let { "$it BPM" } ?: "—"
        binding.tvWorkoutStatHrMax.text = maxHr?.let { "$it BPM" } ?: "—"

        binding.tvSessionHrAvgBig.text = avgHr?.toString() ?: "—"
        binding.tvSessionHrMaxBig.text = maxHr?.toString() ?: "—"
        binding.tvWorkoutHrSampleCount.text = "${samples.size} sampel"

        val paceSec = MiFitnessHealthMapper.computePaceSecPerKm(distMeters, record.avgSpeedMps, durationSec)
        val validSteps = record.steps?.takeIf { it > 0 }
        val cadenceSpm = MiFitnessHealthMapper.computeCadenceSpm(validSteps, durationSec)
        val elev = record.elevationGainMeters?.takeIf { it >= 1.0 }
        val speedKmh = record.avgSpeedMps?.takeIf { it > 0.2 }?.let { it * 3.6 }

        val extraMetrics = buildList {
            paceSec?.let { add(MiFitnessHealthMapper.formatPace(it) to "Tempo rata2") }
            speedKmh?.let { add(String.format(Locale.US, "%.1f km/j", it) to "Kec. rata2") }
            validSteps?.let { add(numberFmt.format(it) to "Langkah") }
            cadenceSpm?.let { add("$it" to "Irama (SPM)") }
            elev?.let { add("+${it.roundToInt()} m" to "Elevasi naik") }
        }
        val extraSlots = listOf(
            Triple(binding.colWorkoutPace, binding.tvWorkoutStatPace, binding.tvWorkoutLabelPace),
            Triple(binding.colWorkoutSteps, binding.tvWorkoutStatSteps, binding.tvWorkoutLabelSteps),
            Triple(binding.colWorkoutCadence, binding.tvWorkoutStatCadence, binding.tvWorkoutLabelCadence),
            Triple(binding.colWorkoutElevation, binding.tvWorkoutStatElevation, binding.tvWorkoutLabelElevation),
            Triple(binding.colWorkoutDistanceRow, binding.tvWorkoutStatSpeedKmh, binding.tvWorkoutLabelSpeedKmh)
        )
        binding.layoutWorkoutMetricsExtra.visibility = if (extraMetrics.isNotEmpty()) View.VISIBLE else View.GONE
        binding.layoutWorkoutMetricsElevation.visibility = if (extraMetrics.size > 3) View.VISIBLE else View.GONE
        extraSlots.forEachIndexed { idx, (col, tvVal, tvLabel) ->
            val entry = extraMetrics.getOrNull(idx)
            if (entry != null) {
                col.visibility = View.VISIBLE
                tvVal.text = entry.first
                tvLabel.text = entry.second
            } else {
                col.visibility = View.INVISIBLE
            }
        }

        binding.tvWorkoutUnavailableNotice.text = if (routePoints.size >= 2 || distMeters != null || validSteps != null) {
            "Split pace per kilometer dan panjang langkah tidak diekspor oleh Mi Fitness ke Health Connect. Seluruh metrik lain berasal langsung dari rekaman latihan Anda."
        } else {
            "Data jarak GPS, rute, tempo, dan langkah belum tersedia pada berkas BLE sesi ini. Ketuk 'Impor semua data Mi Fitness' di Dashboard jika riwayat ini tersedia di Mi Fitness."
        }

        binding.tvSessionTick0.text = "00:00:00"
        binding.tvSessionTick1.text = if (durationSec > 0) formatHms(durationSec / 2) else "--:--:--"
        binding.tvSessionTick2.text = if (durationSec > 0) formattedHms else "--:--:--"

        if (samples.size >= 2) {
            binding.tvWorkoutHrCurveEmpty.visibility = View.GONE
            binding.workoutHrCurve.visibility = View.VISIBLE
            binding.workoutHrCurve.setAccentColor(
                ContextCompat.getColor(this, R.color.hr_zone_vo2max),
                ContextCompat.getColor(this, R.color.hr_chart_fill)
            )
            binding.workoutHrCurve.setRecords(samples)
            binding.tvWorkoutZoneHint.text = "Distribusi zona dihitung dari ${samples.size} sampel HR sesi ini"
        } else {
            binding.workoutHrCurve.setRecords(emptyList())
            binding.tvWorkoutHrCurveEmpty.visibility = View.VISIBLE
            binding.tvWorkoutZoneHint.text = "Grafik kurva & durasi zona membutuhkan sampel HR kontinu selama sesi."
        }

        renderSessionZones(samples)
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupRouteMap() {
        binding.workoutMapView.addOnDidFailLoadingMapListener {
            if (!isDestroyed && currentRoute.size >= 2) {
                routeStyleLoaded = false
                routeMapRendered = false
                binding.workoutRouteView.visibility = View.VISIBLE
                binding.workoutMapStatus.visibility = View.VISIBLE
                binding.tvWorkoutMapStatus.text = "Peta belum tersedia · rute tetap tersimpan"
                binding.btnRetryWorkoutMap.visibility = View.VISIBLE
            }
        }
        binding.workoutMapView.addOnDidFinishRenderingMapListener { fully ->
            if (fully && routeStyleLoaded && !routeMapRendered && !isDestroyed) {
                routeMapRendered = true
                binding.workoutRouteView.visibility = View.GONE
                binding.workoutMapStatus.visibility = View.GONE
            }
        }
        binding.workoutMapView.setOnTouchListener { view, event ->
            view.parent.requestDisallowInterceptTouchEvent(
                event.actionMasked != MotionEvent.ACTION_UP && event.actionMasked != MotionEvent.ACTION_CANCEL
            )
            false
        }
        binding.workoutMapView.getMapAsync { map ->
            routeMap = map
            map.uiSettings.isTiltGesturesEnabled = false
            loadRouteMap()
        }
        binding.btnFitWorkoutRoute.setOnClickListener { fitRoute() }
        binding.btnRetryWorkoutMap.setOnClickListener { loadRouteMap() }
    }

    private fun loadRouteMap() {
        val map = routeMap ?: return
        if (currentRoute.size < 2 || isDestroyed) return
        routeStyleLoaded = false
        routeMapRendered = false
        binding.workoutRouteView.visibility = View.VISIBLE
        binding.workoutMapStatus.visibility = View.VISIBLE
        binding.tvWorkoutMapStatus.text = "Memuat peta…"
        binding.btnRetryWorkoutMap.visibility = View.GONE
        map.setStyle("https://tiles.openfreemap.org/styles/dark") { style ->
            if (isDestroyed) return@setStyle
            val points = currentRoute.map { Point.fromLngLat(it.lon, it.lat) }
            style.addSource(GeoJsonSource("workout-route", LineString.fromLngLats(points)))
            style.addLayer(LineLayer("workout-route-outline", "workout-route").withProperties(
                lineColor("#0D121A"), lineWidth(8f),
                lineCap(Property.LINE_CAP_ROUND), lineJoin(Property.LINE_JOIN_ROUND)
            ))
            style.addLayer(LineLayer("workout-route-line", "workout-route").withProperties(
                lineColor("#6FE0BD"), lineWidth(4f),
                lineCap(Property.LINE_CAP_ROUND), lineJoin(Property.LINE_JOIN_ROUND)
            ))
            listOf("start" to points.first(), "finish" to points.last()).forEach { (name, point) ->
                val source = "workout-$name"
                style.addSource(GeoJsonSource(source, FeatureCollection.fromFeature(Feature.fromGeometry(point))))
                style.addLayer(CircleLayer(source, source).withProperties(
                    circleRadius(6f), circleColor(if (name == "start") "#6FE0BD" else "#FFA09A"),
                    circleStrokeColor("#FFFFFF"), circleStrokeWidth(2f)
                ))
            }
            routeStyleLoaded = true
            fitRoute()
        }
    }

    private fun fitRoute() {
        val map = routeMap ?: return
        if (!routeStyleLoaded || currentRoute.size < 2) return
        val points = currentRoute.map { LatLng(it.lat, it.lon) }
        binding.workoutMapView.post {
            if (isDestroyed) return@post
            val bounds = LatLngBounds.Builder().includes(points).build()
            val d = resources.displayMetrics.density
            val update = if (bounds.latitudeSpan < 0.00001 && bounds.longitudeSpan < 0.00001) {
                CameraUpdateFactory.newLatLngZoom(points.first(), 16.0)
            } else {
                CameraUpdateFactory.newLatLngBounds(bounds, (28 * d).toInt(), (64 * d).toInt(), (28 * d).toInt(), (32 * d).toInt())
            }
            map.moveCamera(update)
        }
    }

    override fun onStart() { super.onStart(); binding.workoutMapView.onStart() }
    override fun onResume() { super.onResume(); binding.workoutMapView.onResume() }
    override fun onPause() { binding.workoutMapView.onPause(); super.onPause() }
    override fun onStop() { binding.workoutMapView.onStop(); super.onStop() }
    override fun onLowMemory() { super.onLowMemory(); binding.workoutMapView.onLowMemory() }
    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        binding.workoutMapView.onSaveInstanceState(outState)
    }
    override fun onDestroy() { binding.workoutMapView.onDestroy(); super.onDestroy() }

    private fun renderSessionZones(samples: List<HeartRateEntity>) {
        var lightSec = 0L
        var intensiveSec = 0L
        var aerobicSec = 0L
        var anaerobicSec = 0L
        var vo2Sec = 0L

        if (samples.size >= 2) {
            for (i in samples.indices) {
                val cur = samples[i]
                val dt = if (i < samples.lastIndex) {
                    ((samples[i + 1].timestamp - cur.timestamp) / 1000L).coerceIn(1L, 120L)
                } else {
                    15L
                }
                when {
                    cur.bpm < 114 -> lightSec += dt
                    cur.bpm < 133 -> intensiveSec += dt
                    cur.bpm < 152 -> aerobicSec += dt
                    cur.bpm < 171 -> anaerobicSec += dt
                    else -> vo2Sec += dt
                }
            }
        }

        val maxSec = maxOf(lightSec, intensiveSec, aerobicSec, anaerobicSec, vo2Sec, 1L)
        applySessionZoneBar(binding.trackWZoneLight, binding.barWZoneLight, binding.tvWZoneLightTime, lightSec, maxSec)
        applySessionZoneBar(binding.trackWZoneIntensive, binding.barWZoneIntensive, binding.tvWZoneIntensiveTime, intensiveSec, maxSec)
        applySessionZoneBar(binding.trackWZoneAerobic, binding.barWZoneAerobic, binding.tvWZoneAerobicTime, aerobicSec, maxSec)
        applySessionZoneBar(binding.trackWZoneAnaerobic, binding.barWZoneAnaerobic, binding.tvWZoneAnaerobicTime, anaerobicSec, maxSec)
        applySessionZoneBar(binding.trackWZoneVo2, binding.barWZoneVo2, binding.tvWZoneVo2Time, vo2Sec, maxSec)
    }

    private fun applySessionZoneBar(
        track: View,
        bar: View,
        timeLabel: TextView,
        seconds: Long,
        maxSeconds: Long
    ) {
        timeLabel.text = formatHms(seconds.toInt())
        track.post {
            val w = track.width
            if (w <= 0) return@post
            val frac = if (seconds <= 0L) 0f else (seconds.toFloat() / maxSeconds).coerceIn(0.04f, 1f)
            val lp = bar.layoutParams
            lp.width = (w * frac).roundToInt()
            bar.layoutParams = lp
        }
    }

    private fun formatHms(totalSeconds: Int): String {
        val h = totalSeconds / 3600
        val m = (totalSeconds % 3600) / 60
        val s = totalSeconds % 60
        return String.format(Locale.US, "%02d:%02d:%02d", h, m, s)
    }

    private fun exportWorkoutSummary(uri: Uri) {
        val rec = currentRecord ?: return
        val samplesSnapshot = sessionHrSamples.toList()
        val durationSec = resolveDurationSeconds(rec)
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val distKm = rec.distanceMeters?.takeIf { it >= 10.0 }?.div(1000.0)
                val paceSec = MiFitnessHealthMapper.computePaceSecPerKm(rec.distanceMeters, rec.avgSpeedMps, durationSec)
                val cadenceSpm = MiFitnessHealthMapper.computeCadenceSpm(rec.steps, durationSec)
                val sb = StringBuilder().apply {
                    appendLine("RW3-DirectSync · Ringkasan Sesi Olahraga")
                    appendLine("Olahraga: ${SportTypeCatalog.displayName(rec.sportType)} (type=${rec.sportType})")
                    appendLine("Mulai: ${dateTimeFmt.format(Date(rec.timestamp))}")
                    appendLine("Durasi: ${formatHms(durationSec)}")
                    if (distKm != null) appendLine("Jarak: ${String.format(Locale.US, "%.2f km", distKm)}")
                    if (paceSec != null) appendLine("Tempo rata2: ${MiFitnessHealthMapper.formatPace(paceSec)} /km")
                    if (rec.steps != null && rec.steps > 0) appendLine("Langkah: ${rec.steps}")
                    if (cadenceSpm != null) appendLine("Irama rata2: $cadenceSpm SPM")
                    if (rec.elevationGainMeters != null && rec.elevationGainMeters >= 1.0) {
                        appendLine("Elevasi naik: +${rec.elevationGainMeters.roundToInt()} m")
                    }
                    appendLine("Kalori aktif: ${rec.calories?.let { "$it kcal" } ?: "—"}")
                    appendLine("HR Rata-rata: ${rec.averageHeartRate?.let { "$it BPM" } ?: "—"}")
                    appendLine("HR Min / Maks: ${rec.minHeartRate ?: "—"} / ${rec.maxHeartRate ?: "—"} BPM")
                    appendLine("Sumber file: ${rec.sourceFileId}")
                    appendLine("Sampel HR dalam sesi (${samplesSnapshot.size}):")
                    samplesSnapshot.forEach { hr ->
                        appendLine("  ${SimpleDateFormat("HH:mm:ss", Locale.US).format(Date(hr.timestamp))},${hr.bpm}")
                    }
                }
                contentResolver.openOutputStream(uri)?.use { out ->
                    out.write(sb.toString().toByteArray(Charsets.UTF_8))
                }
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@WorkoutDetailActivity, "Ringkasan sesi diekspor.", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@WorkoutDetailActivity, "Gagal mengekspor: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }
}

