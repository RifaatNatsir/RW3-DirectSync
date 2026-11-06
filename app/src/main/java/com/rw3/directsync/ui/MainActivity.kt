package com.rw3.directsync.ui

import android.Manifest
import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.text.Editable
import android.text.InputFilter
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.rw3.directsync.R
import com.rw3.directsync.ble.BleSyncService
import com.rw3.directsync.data.db.entity.ActivityRecordEntity
import com.rw3.directsync.databinding.ActivityMainBinding
import com.rw3.directsync.databinding.DialogAppWhitelistBinding
import com.rw3.directsync.databinding.ItemWhitelistAppBinding
import com.rw3.directsync.notification.Rw3NotificationListenerService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.NumberFormat
import java.util.Locale

/**
 * RW3-DirectSync Dashboard Activity:
 * - Google Stitch dark theme with high-contrast buttons & crisp typography
 * - Dedicated full-screen BLE GATT Terminal tab (no unwanted auto-scrolling on dashboard)
 * - PPG Optical Heart Rate real-time stream & smooth Canvas ECG sparkline
 * - Daily Activity gauges (Steps, Calories) & secondary fitness metrics
 * - Health Checkups (SpO2, Stress, Spot HR) & Workout History cards
 * - Sleep Architecture multi-segment stage visualization
 * - Room SQLite telemetry storage monitoring & Webhook POST pipeline
 */
class MainActivity : AppCompatActivity() {

    private enum class NavTab {
        DASHBOARD,
        ACTIVITY,
        TERMINAL
    }

    private val viewModel: MainViewModel by viewModels()
    private lateinit var binding: ActivityMainBinding

    private var bleService: BleSyncService? = null
    private var serviceStateJob: Job? = null
    private var pendingActionAfterPermission: (() -> Unit)? = null

    private val numberFormat = NumberFormat.getNumberInstance(Locale.US)

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        val allGranted = grants.values.all { it }
        if (allGranted) {
            pendingActionAfterPermission?.invoke()
            pendingActionAfterPermission = null
        } else {
            Toast.makeText(
                this,
                "Izin Bluetooth, Lokasi, & Notifikasi diperlukan untuk koneksi BLE.",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val localBinder = binder as? BleSyncService.LocalBinder ?: return
            val service = localBinder.getService()
            bleService = service
            serviceStateJob?.cancel()
            serviceStateJob = lifecycleScope.launch {
                repeatOnLifecycle(Lifecycle.State.STARTED) {
                    launch {
                        service.syncState.collect { state ->
                            viewModel.onServiceStateChanged(state)
                        }
                    }
                    launch {
                        service.discoveredDevices.collect { devices ->
                            viewModel.onDiscoveredDevicesChanged(devices)
                        }
                    }
                    launch {
                        service.diagnosticLogs.collect { logs ->
                            viewModel.onDiagnosticLogsChanged(logs)
                        }
                    }
                    launch {
                        service.batteryLevel.collect { battery ->
                            viewModel.onBatteryLevelChanged(battery)
                        }
                    }
                    launch {
                        service.heartRateBpm.collect { bpm ->
                            viewModel.onHeartRateBpmChanged(bpm)
                        }
                    }
                    launch {
                        service.isHrStreaming.collect { streaming ->
                            viewModel.onHrStreamingChanged(streaming)
                        }
                    }
                }
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            serviceStateJob?.cancel()
            bleService = null
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Customize gauge meter colors
        binding.gaugeDailySteps.progressColor = ContextCompat.getColor(this, R.color.primary_mint)
        binding.gaugeDailyCalories.progressColor = ContextCompat.getColor(this, R.color.heart_rate_coral)

        setupListeners()
        observeViewModel()
        updateNotificationUiState()
        selectTab(NavTab.DASHBOARD)
    }

    override fun onResume() {
        super.onResume()
        updateNotificationUiState()
    }

    private fun updateNotificationUiState() {
        val hasAccess = Rw3NotificationListenerService.isNotificationAccessGranted(this)
        if (hasAccess) {
            binding.tvNotificationAccessBadge.text = "Akses Aktif"
            binding.tvNotificationAccessBadge.setTextColor(ContextCompat.getColor(this, R.color.primary_mint))
            binding.btnNotificationPermission.text = "Pengaturan Izin"
            binding.tvNotificationDescription.text = "Pesan aplikasi (WhatsApp, Telegram, SMS, dll) dan panggilan telepon diteruskan langsung ke layar Redmi Watch 3."
        } else {
            binding.tvNotificationAccessBadge.text = "Akses Belum Aktif"
            binding.tvNotificationAccessBadge.setTextColor(ContextCompat.getColor(this, R.color.heart_rate_coral))
            binding.btnNotificationPermission.text = "Aktifkan Izin"
            binding.tvNotificationDescription.text = "Akses Notifikasi Android belum diberikan. Ketuk 'Aktifkan Izin' agar jam tangan dapat menerima notifikasi HP."
        }

        val isWhitelistMode = Rw3NotificationListenerService.isWhitelistModeEnabled(this)
        val whitelistedCount = Rw3NotificationListenerService.getWhitelistedPackages(this).size
        if (isWhitelistMode) {
            binding.tvWhitelistSummary.text = "Whitelist Aktif: $whitelistedCount aplikasi diizinkan"
            binding.tvWhitelistSummary.setTextColor(ContextCompat.getColor(this, R.color.primary_mint))
        } else {
            binding.tvWhitelistSummary.text = "Semua Aplikasi Diizinkan (Whitelist Mati)"
            binding.tvWhitelistSummary.setTextColor(ContextCompat.getColor(this, R.color.on_surface_variant))
        }
    }

    override fun onStart() {
        super.onStart()
        Intent(this, BleSyncService::class.java).also { intent ->
            bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
        }
    }

    override fun onStop() {
        super.onStop()
        if (bleService != null) {
            unbindService(serviceConnection)
            bleService = null
        }
    }

    private fun setupListeners() {
        // Copy MAC Address
        binding.btnCopyMac.setOnClickListener {
            copyTextToClipboard("RW3-MAC", binding.tvMacAddressDisplay.text.toString())
            Toast.makeText(this, "MAC Address disalin ke clipboard", Toast.LENGTH_SHORT).show()
        }

        // Tap MAC bar or Profile button to edit credentials
        binding.tvMacAddressDisplay.setOnClickListener {
            showCredentialsSettingsDialog()
        }
        binding.btnHeaderProfile.setOnClickListener {
            showCredentialsSettingsDialog()
        }

        // Fast Connection Action Buttons
        binding.btnConnectMain.setOnClickListener {
            ensurePermissionsAndRun { validateAndStartBleService() }
        }

        binding.btnScanBlePicker.setOnClickListener {
            ensurePermissionsAndRun { startScanAndShowPicker() }
        }

        binding.btnDisconnectMain.setOnClickListener {
            stopBleService()
        }

        // PPG Sensor Trigger
        binding.btnToggleHeartRate.setOnClickListener {
            triggerStartHeartRate()
        }

        // Card 1B: Smart Notifications
        binding.btnNotificationPermission.setOnClickListener {
            Rw3NotificationListenerService.openNotificationAccessSettings(this)
        }

        binding.btnTestNotification.setOnClickListener {
            val isConnected = (bleService?.syncState?.value is BleSyncService.SyncState.Authenticated)
            if (isConnected) {
                val intent = Intent(this, BleSyncService::class.java).apply {
                    action = BleSyncService.ACTION_SEND_TEST_NOTIFICATION
                }
                startService(intent)
                Toast.makeText(this, "Notifikasi tes dikirim ke Redmi Watch 3!", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "Hubungkan jam terlebih dahulu via tombol 'Hubungkan'.", Toast.LENGTH_LONG).show()
            }
        }

        binding.btnFilterApps.setOnClickListener {
            showAppWhitelistDialog()
        }

        // Card 7: Sync Semua Data (Sleep, Workouts, Activities, Battery)
        binding.btnSyncAll.setOnClickListener {
            triggerSyncAll()
        }

        // Webhook Pipeline
        binding.boxWebhookEndpoint.setOnClickListener {
            showEditWebhookDialog()
        }

        binding.btnPushWebhookMain.setOnClickListener {
            triggerWebhookExport()
        }

        // Terminal Controls
        binding.btnCopyTerminalLogs.setOnClickListener {
            copyLogsToClipboard()
        }

        binding.btnClearTerminalLogs.setOnClickListener {
            bleService?.clearDiagnosticLogs()
            viewModel.clearDiagnosticLogs()
            binding.tvTerminalLogOutput.text = "Logs cleared. Menunggu event baru..."
        }

        // Bottom Navigation Tabs
        binding.navTabDash.setOnClickListener {
            selectTab(NavTab.DASHBOARD)
        }

        binding.navTabActivity.setOnClickListener {
            selectTab(NavTab.ACTIVITY)
        }

        binding.navTabTerminal.setOnClickListener {
            selectTab(NavTab.TERMINAL)
        }

        binding.navTabSettings.setOnClickListener {
            showCredentialsSettingsDialog()
        }
    }

    private fun selectTab(tab: NavTab) {
        val mint = ContextCompat.getColor(this, R.color.primary_mint)
        val muted = ContextCompat.getColor(this, R.color.on_surface_variant)

        when (tab) {
            NavTab.DASHBOARD -> {
                binding.layoutDashboardContainer.visibility = View.VISIBLE
                binding.layoutTerminalContainer.visibility = View.GONE
                binding.tvHeaderAppTitle.text = "Dashboard"

                binding.imgNavDash.imageTintList = ColorStateList.valueOf(mint)
                binding.tvNavDash.setTextColor(mint)

                binding.imgNavActivity.imageTintList = ColorStateList.valueOf(muted)
                binding.tvNavActivity.setTextColor(muted)

                binding.imgNavTerminal.imageTintList = ColorStateList.valueOf(muted)
                binding.tvNavTerminal.setTextColor(muted)

                binding.layoutDashboardContainer.smoothScrollTo(0, 0)
            }
            NavTab.ACTIVITY -> {
                binding.layoutDashboardContainer.visibility = View.VISIBLE
                binding.layoutTerminalContainer.visibility = View.GONE
                binding.tvHeaderAppTitle.text = "Aktivitas"

                binding.imgNavDash.imageTintList = ColorStateList.valueOf(muted)
                binding.tvNavDash.setTextColor(muted)

                binding.imgNavActivity.imageTintList = ColorStateList.valueOf(mint)
                binding.tvNavActivity.setTextColor(mint)

                binding.imgNavTerminal.imageTintList = ColorStateList.valueOf(muted)
                binding.tvNavTerminal.setTextColor(muted)

                binding.layoutDashboardContainer.post {
                    binding.layoutDashboardContainer.smoothScrollTo(0, binding.cardDailyActivity.top)
                }
            }
            NavTab.TERMINAL -> {
                binding.layoutDashboardContainer.visibility = View.GONE
                binding.layoutTerminalContainer.visibility = View.VISIBLE
                binding.tvHeaderAppTitle.text = "BLE Terminal"

                binding.imgNavDash.imageTintList = ColorStateList.valueOf(muted)
                binding.tvNavDash.setTextColor(muted)

                binding.imgNavActivity.imageTintList = ColorStateList.valueOf(muted)
                binding.tvNavActivity.setTextColor(muted)

                binding.imgNavTerminal.imageTintList = ColorStateList.valueOf(mint)
                binding.tvNavTerminal.setTextColor(mint)

                if (binding.switchAutoScroll.isChecked) {
                    binding.terminalScrollView.post {
                        binding.terminalScrollView.scrollTo(0, binding.tvTerminalLogOutput.bottom)
                    }
                }
            }
        }
    }

    @SuppressLint("SetTextI18n")
    private fun observeViewModel() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                // 1. MAC Address
                launch {
                    viewModel.macAddress.collect { mac ->
                        binding.tvMacAddressDisplay.text = mac
                    }
                }

                // 2. Webhook URL
                launch {
                    viewModel.webhookUrl.collect { url ->
                        binding.tvWebhookUrlPreview.text = url
                    }
                }

                // 3. Battery Level
                launch {
                    viewModel.batteryLevel.collect { battery ->
                        val batteryStr = if (battery != null && battery > 0) "$battery%" else "--%"
                        binding.tvHeaderBattery.text = " $batteryStr"
                        binding.tvDeviceBattery.text = " $batteryStr"
                    }
                }

                // 4. Live BPM & Waveform
                launch {
                    viewModel.liveBpm.collect { bpm ->
                        if (bpm != null && bpm > 0) {
                            binding.tvLiveBpmValue.text = bpm.toString()
                            binding.sparklineWaveView.updateBpm(bpm)
                        } else {
                            binding.tvLiveBpmValue.text = "--"
                        }
                    }
                }

                // 4b. 24-Hour Heart Rate Timeline & Stats
                launch {
                    viewModel.heartRate24hRecords.collect { records ->
                        binding.sparklineWaveView.setHeartRateRecords(records)
                    }
                }

                launch {
                    viewModel.heartRate24hStats.collect { stats ->
                        if (stats.count > 0 && stats.avgBpm != null) {
                            binding.tvHr24hStats.text = "Min: ${stats.minBpm} • Rata: ${stats.avgBpm} • Maks: ${stats.maxBpm} BPM"
                        } else {
                            binding.tvHr24hStats.text = "24 Jam Terakhir"
                        }
                    }
                }

                // 4b. On-Demand Eco HR Streaming Toggle State
                launch {
                    viewModel.isHrStreaming.collect { streaming ->
                        if (streaming) {
                            binding.btnToggleHeartRate.text = "Hentikan Stream"
                            binding.tvHrStreamBadge.text = "● Eco Stream (15s)"
                            binding.tvHrStreamBadge.setTextColor(
                                ContextCompat.getColor(this@MainActivity, R.color.heart_rate_coral)
                            )
                        } else {
                            binding.btnToggleHeartRate.text = "Mulai Ukur HR"
                            binding.tvHrStreamBadge.text = "○ Sensor Hemat Daya"
                            binding.tvHrStreamBadge.setTextColor(
                                ContextCompat.getColor(this@MainActivity, R.color.primary_mint)
                            )
                        }
                    }
                }

                // 5. Stored Records & Unsynced Queue
                launch {
                    viewModel.storedRecordsCount.collect { count ->
                        binding.tvStoredRecordsCount.text = numberFormat.format(count)
                    }
                }

                launch {
                    viewModel.unsyncedRecordsCount.collect { count ->
                        binding.tvUnsyncedQueueCount.text = numberFormat.format(count)
                    }
                }

                // 6. Sync & GATT State
                launch {
                    viewModel.syncState.collect { state ->
                        updateUiForSyncState(state)
                    }
                }

                // 7. Diagnostic Terminal Logs (Auto-scroll without focus request)
                launch {
                    viewModel.diagnosticLogs.collect { logs ->
                        if (logs.isNotEmpty()) {
                            binding.tvTerminalLogOutput.text = logs.joinToString("\n")
                            if (binding.layoutTerminalContainer.visibility == View.VISIBLE && binding.switchAutoScroll.isChecked) {
                                binding.terminalScrollView.post {
                                    binding.terminalScrollView.scrollTo(0, binding.tvTerminalLogOutput.bottom)
                                }
                            }
                        }
                    }
                }

                // 8. Card 3: Aktivitas Harian (Langkah, Kalori, HR Istirahat, Stand Hours)
                launch {
                    viewModel.dailyActivity.collect { record ->
                        if (record != null) {
                            val steps = record.steps ?: 0
                            val cals = record.calories ?: 0
                            binding.tvDailyStepsValue.text = numberFormat.format(steps)
                            binding.tvDailyCaloriesValue.text = numberFormat.format(cals)

                            val stepsPct = ((steps.toFloat() / 10000f) * 100f).coerceIn(0f, 100f)
                            val calsPct = ((cals.toFloat() / 600f) * 100f).coerceIn(0f, 100f)

                            binding.tvDailyStepsPercent.text = "${stepsPct.toInt()}% tercapai"
                            binding.tvDailyCaloriesPercent.text = "${calsPct.toInt()}% tercapai"

                            binding.gaugeDailySteps.progress = stepsPct
                            binding.gaugeDailyCalories.progress = calsPct

                            record.restingHeartRate?.let {
                                binding.tvRestingHrValue.text = "$it BPM"
                            }
                            record.standingHours?.let {
                                binding.tvStandHoursValue.text = "$it / 12j"
                            }
                        } else {
                            // Default initial progress for preview
                            binding.gaugeDailySteps.progress = 84.2f
                            binding.gaugeDailyCalories.progress = 77.0f
                        }
                    }
                }

                // 9. Card 4: Pengukuran Kesehatan (SpO2, Stres, HR Spot)
                launch {
                    viewModel.latestSpo2.collect { record ->
                        if (record != null && record.spo2 != null) {
                            binding.tvSpo2Value.text = "${record.spo2}%"
                        }
                    }
                }

                launch {
                    viewModel.latestStress.collect { record ->
                        if (record != null && record.stress != null) {
                            binding.tvStressValue.text = "${record.stress}/100"
                        }
                    }
                }

                launch {
                    viewModel.latestManualHr.collect { record ->
                        if (record != null && record.averageHeartRate != null) {
                            binding.tvSpotHrValue.text = "${record.averageHeartRate} BPM"
                        }
                    }
                }

                // 10. Card 5: Riwayat Olahraga
                launch {
                    viewModel.workouts.collect { records ->
                        if (records.isNotEmpty()) {
                            binding.layoutWorkoutHistoryList.removeAllViews()
                            records.take(5).forEach { record ->
                                addWorkoutRowView(record)
                            }
                        }
                    }
                }

                // 11. Card 6: Arsitektur Tidur Summary
                launch {
                    viewModel.sleepSummary.collect { summary ->
                        updateSleepSummaryUi(summary)
                    }
                }
            }
        }
    }

    private fun addWorkoutRowView(record: ActivityRecordEntity) {
        val density = resources.displayMetrics.density
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.bg_subcard)
            val pad = (11 * density).toInt()
            setPadding(pad, pad, pad, pad)
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = (8 * density).toInt()
            }
            layoutParams = lp
        }

        val topRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val iconContainer = FrameLayout(this).apply {
            val size = (30 * density).toInt()
            layoutParams = LinearLayout.LayoutParams(size, size)
            setBackgroundResource(R.drawable.bg_chip)
        }
        val icon = ImageView(this).apply {
            val size = (18 * density).toInt()
            layoutParams = FrameLayout.LayoutParams(size, size, Gravity.CENTER)
            val iconRes = when (record.sportType) {
                1 -> R.drawable.ic_directions_run
                2, 15 -> R.drawable.ic_nordic_walking
                6, 7 -> R.drawable.ic_pedal_bike
                else -> R.drawable.ic_fitness_center
            }
            setImageResource(iconRes)
            imageTintList = ColorStateList.valueOf(ContextCompat.getColor(this@MainActivity, R.color.primary_mint))
        }
        iconContainer.addView(icon)
        topRow.addView(iconContainer)

        val titleCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val lp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = (8 * density).toInt()
            }
            layoutParams = lp
        }
        val titleText = TextView(this).apply {
            text = sportName(record.sportType)
            textSize = 13f
            setTypeface(null, Typeface.BOLD)
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.on_surface))
        }
        val timeText = TextView(this).apply {
            val seconds = record.activeSeconds ?: 0
            val durStr = if (seconds > 0) "${seconds / 60}m ${seconds % 60}s" else "—"
            text = "${metricTime(record.timestamp)} • $durStr"
            textSize = 11f
            typeface = Typeface.MONOSPACE
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.on_surface_variant))
        }
        titleCol.addView(titleText)
        titleCol.addView(timeText)
        topRow.addView(titleCol)

        val calsText = TextView(this).apply {
            text = "${record.calories ?: "—"} kkal"
            textSize = 13f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#F59E0B"))
        }
        topRow.addView(calsText)
        card.addView(topRow)

        val hrText = TextView(this).apply {
            text = "HR Rata/Min/Maks: ${record.averageHeartRate ?: "—"} / ${record.minHeartRate ?: "—"} / ${record.maxHeartRate ?: "—"} BPM"
            textSize = 11f
            typeface = Typeface.MONOSPACE
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.on_surface_variant))
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = (6 * density).toInt()
            }
            layoutParams = lp
        }
        card.addView(hrText)

        binding.layoutWorkoutHistoryList.addView(card)
    }

    private fun metricTime(timestamp: Long): String =
        java.text.SimpleDateFormat("dd MMM HH:mm", Locale.getDefault()).format(java.util.Date(timestamp))

    private fun sportName(type: Int?): String = when (type) {
        1 -> "Lari Luar Ruang"
        2, 15 -> "Jalan Kaki"
        6 -> "Bersepeda Luar"
        7 -> "Sepeda Statis"
        8 -> "Latihan Bebas"
        12 -> "Yoga"
        16 -> "HIIT"
        305 -> "Pilates"
        308 -> "Latihan Kekuatan"
        608 -> "Bulu Tangkis"
        else -> type?.let { "Olahraga #$it" } ?: "Olahraga"
    }

    @SuppressLint("SetTextI18n")
    private fun updateSleepSummaryUi(summary: MainViewModel.SleepSummary) {
        if (summary.hasData) {
            val totalH = summary.totalMinutes / 60
            val totalM = summary.totalMinutes % 60
            binding.tvTotalSleepValue.text = "${totalH}h ${totalM}m"
            binding.tvDeepSleepRatio.text = "${summary.deepRatioPercent}%"
            binding.tvSleepScore.text = summary.sleepScore?.let { "$it/100" } ?: "--/100"
            val dateFormat = java.text.SimpleDateFormat("dd MMM HH:mm", Locale.getDefault())
            binding.tvSleepPeriod.text = "${dateFormat.format(java.util.Date(summary.startTimestamp))} - ${dateFormat.format(java.util.Date(summary.endTimestamp))}"
            binding.tvTotalSleepValue.contentDescription = summary.averageHeartRate?.let {
                "Total tidur ${totalH} jam ${totalM} menit, rata-rata detak jantung $it BPM"
            } ?: binding.tvTotalSleepValue.text

            val totalMin = summary.totalMinutes + summary.awakeMinutes
            val safeTotal = if (totalMin > 0) totalMin.toFloat() else 1f

            val wDeep = (summary.deepMinutes.toFloat() / safeTotal * 100f)
            val wLight = (summary.lightMinutes.toFloat() / safeTotal * 100f)
            val wRem = (summary.remMinutes.toFloat() / safeTotal * 100f)
            val wAwake = (summary.awakeMinutes.toFloat() / safeTotal * 100f)

            (binding.viewSleepBarDeep.layoutParams as? LinearLayout.LayoutParams)?.let {
                it.weight = wDeep
                binding.viewSleepBarDeep.layoutParams = it
            }
            (binding.viewSleepBarLight.layoutParams as? LinearLayout.LayoutParams)?.let {
                it.weight = wLight
                binding.viewSleepBarLight.layoutParams = it
            }
            (binding.viewSleepBarRem.layoutParams as? LinearLayout.LayoutParams)?.let {
                it.weight = wRem
                binding.viewSleepBarRem.layoutParams = it
            }
            (binding.viewSleepBarAwake.layoutParams as? LinearLayout.LayoutParams)?.let {
                it.weight = wAwake
                binding.viewSleepBarAwake.layoutParams = it
            }

            binding.tvLegendSleepDeep.text = "● Deep ${summary.deepMinutes / 60}h ${summary.deepMinutes % 60}m"
            binding.tvLegendSleepLight.text = "● Light ${summary.lightMinutes / 60}h ${summary.lightMinutes % 60}m"
            binding.tvLegendSleepRem.text = "● REM ${summary.remMinutes / 60}h ${summary.remMinutes % 60}m"
            binding.tvLegendSleepAwake.text = "● Terjaga ${summary.awakeMinutes}m"
        } else {
            binding.tvSleepPeriod.text = "Belum ada data tidur terverifikasi"
            binding.tvTotalSleepValue.text = "--h --m"
            binding.tvDeepSleepRatio.text = "--%"
            binding.tvSleepScore.text = "--/100"

            binding.tvLegendSleepDeep.text = "● Deep --m"
            binding.tvLegendSleepLight.text = "● Light --m"
            binding.tvLegendSleepRem.text = "● REM --m"
            binding.tvLegendSleepAwake.text = "● Terjaga --m"
        }
    }

    @SuppressLint("SetTextI18n")
    private fun updateUiForSyncState(state: BleSyncService.SyncState) {
        val mintColor = ContextCompat.getColor(this, R.color.primary_mint)
        val cyanColor = ContextCompat.getColor(this, R.color.secondary_cyan)
        val coralColor = ContextCompat.getColor(this, R.color.heart_rate_coral)
        val mutedColor = ContextCompat.getColor(this, R.color.on_surface_variant)

        when (state) {
            is BleSyncService.SyncState.Idle -> {
                binding.tvHeaderGattStatus.text = " GATT: IDLE"
                binding.tvHeaderGattStatus.setTextColor(mutedColor)
                binding.imgHeaderGattDot.setImageResource(R.drawable.dot_pulse_coral)
                binding.tvDeviceStatusTag.text = "○ Idle"
                binding.tvDeviceStatusTag.setTextColor(mutedColor)
            }

            is BleSyncService.SyncState.Connecting -> {
                binding.tvHeaderGattStatus.text = " GATT: CONNECTING"
                binding.tvHeaderGattStatus.setTextColor(cyanColor)
                binding.imgHeaderGattDot.setImageResource(R.drawable.dot_pulse_green)
                binding.tvDeviceStatusTag.text = "⏳ Connecting..."
                binding.tvDeviceStatusTag.setTextColor(cyanColor)
            }

            is BleSyncService.SyncState.DiscoveringServices -> {
                binding.tvHeaderGattStatus.text = " GATT: DISCOVERING"
                binding.tvHeaderGattStatus.setTextColor(cyanColor)
                binding.tvDeviceStatusTag.text = "⚡ Discovering"
                binding.tvDeviceStatusTag.setTextColor(cyanColor)
            }

            is BleSyncService.SyncState.Authenticating -> {
                binding.tvHeaderGattStatus.text = " AUTH: PROG"
                binding.tvHeaderGattStatus.setTextColor(cyanColor)
                binding.tvDeviceStatusTag.text = "🔑 Authenticating"
                binding.tvDeviceStatusTag.setTextColor(cyanColor)
            }

            is BleSyncService.SyncState.Authenticated -> {
                binding.tvHeaderGattStatus.text = " GATT: CONNECTED"
                binding.tvHeaderGattStatus.setTextColor(mintColor)
                binding.imgHeaderGattDot.setImageResource(R.drawable.dot_pulse_green)
                binding.tvDeviceStatusTag.text = " Tersambung"
                binding.tvDeviceStatusTag.setTextColor(mintColor)
            }

            is BleSyncService.SyncState.StreamingHeartRate -> {
                binding.tvHeaderGattStatus.text = " HR: ${state.latestBpm} BPM"
                binding.tvHeaderGattStatus.setTextColor(coralColor)
                binding.imgHeaderGattDot.setImageResource(R.drawable.dot_pulse_green)
                binding.tvDeviceStatusTag.text = " Tersambung"
                binding.tvDeviceStatusTag.setTextColor(mintColor)
                binding.tvLiveBpmValue.text = state.latestBpm.toString()
                binding.sparklineWaveView.updateBpm(state.latestBpm)
            }

            is BleSyncService.SyncState.SyncingSleep -> {
                binding.tvHeaderGattStatus.text = " ACTIVITY SYNC"
                binding.tvHeaderGattStatus.setTextColor(cyanColor)
                binding.tvDeviceStatusTag.text = "🌙 Syncing (${state.bytesBuffered} B)"
                binding.tvDeviceStatusTag.setTextColor(cyanColor)
            }

            is BleSyncService.SyncState.SleepSyncCompleted -> {
                binding.tvHeaderGattStatus.text = " GATT: CONNECTED"
                binding.tvDeviceStatusTag.text = "✓ Sleep Saved (${state.stagesSaved})"
                binding.tvDeviceStatusTag.setTextColor(mintColor)
                Toast.makeText(this, "Berhasil menyimpan ${state.stagesSaved} stage tidur ke SQLite!", Toast.LENGTH_SHORT).show()
            }

            is BleSyncService.SyncState.WebhookDispatched -> {
                binding.tvDeviceStatusTag.text = "✓ Dispatched"
                binding.tvDeviceStatusTag.setTextColor(mintColor)
                binding.tvWebhookLastAck.text = "● Push Berhasil (${state.hrCount} HR, ${state.sleepCount} Sleep, ${state.activityCount} aktivitas)"
                binding.tvWebhookLastAck.setTextColor(mintColor)
            }

            is BleSyncService.SyncState.Disconnected -> {
                binding.tvHeaderGattStatus.text = " GATT: DISCONNECTED"
                binding.tvHeaderGattStatus.setTextColor(coralColor)
                binding.imgHeaderGattDot.setImageResource(R.drawable.dot_pulse_coral)
                binding.tvDeviceStatusTag.text = "✕ Terputus"
                binding.tvDeviceStatusTag.setTextColor(coralColor)
            }

            is BleSyncService.SyncState.Error -> {
                binding.tvHeaderGattStatus.text = " GATT: ERROR"
                binding.tvHeaderGattStatus.setTextColor(coralColor)
                binding.imgHeaderGattDot.setImageResource(R.drawable.dot_pulse_coral)
                binding.tvDeviceStatusTag.text = "⚠ Error"
                binding.tvDeviceStatusTag.setTextColor(coralColor)
            }
        }
    }

    private fun ensurePermissionsAndRun(action: () -> Unit) {
        val requiredPermissions = buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                add(Manifest.permission.BLUETOOTH_SCAN)
                add(Manifest.permission.BLUETOOTH_CONNECT)
            } else {
                add(Manifest.permission.ACCESS_FINE_LOCATION)
                add(Manifest.permission.ACCESS_COARSE_LOCATION)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        val missing = requiredPermissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (missing.isEmpty()) {
            action()
        } else {
            pendingActionAfterPermission = action
            permissionLauncher.launch(missing.toTypedArray())
        }
    }

    private fun startScanAndShowPicker() {
        val intent = Intent(this, BleSyncService::class.java).apply {
            action = BleSyncService.ACTION_SCAN_DEVICES
        }
        ContextCompat.startForegroundService(this, intent)

        Toast.makeText(
            this,
            "Memindai perangkat BLE selama 4 detik...",
            Toast.LENGTH_SHORT
        ).show()

        window.decorView.postDelayed({
            val devices = viewModel.discoveredDevices.value
            if (devices.isEmpty()) {
                AlertDialog.Builder(this)
                    .setTitle("Perangkat Tidak Ditemukan")
                    .setMessage(
                        "Tidak ada sinyal BLE yang tertangkap.\n\n" +
                            "Tips:\n" +
                            "1. Pastikan GPS & Bluetooth aktif di HP.\n" +
                            "2. Layar Redmi Watch 3 harus dalam keadaan menyala.\n" +
                            "3. Cek Settings -> About di jam tangan Anda."
                    )
                    .setPositiveButton("OK", null)
                    .show()
            } else {
                val items = devices.map { dev ->
                    val bondTag = if (dev.isBonded) " [BONDED]" else ""
                    val rssiTag = if (dev.rssi != 0) " (${dev.rssi} dBm)" else ""
                    "${dev.name}\n${dev.macAddress}$bondTag$rssiTag"
                }.toTypedArray()

                AlertDialog.Builder(this)
                    .setTitle("Pilih Perangkat BLE Jam Tangan")
                    .setItems(items) { _, which ->
                        val chosen = devices[which]
                        viewModel.saveCredentialsAndWebhook(
                            mac = chosen.macAddress,
                            key = viewModel.authKey.value,
                            webhook = viewModel.webhookUrl.value
                        )
                        Toast.makeText(
                            this,
                            "MAC Address diatur ke: ${chosen.macAddress}",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                    .setNegativeButton("Batal", null)
                    .show()
            }
        }, 4_000)
    }

    private fun validateAndStartBleService() {
        val result = viewModel.saveCredentialsAndWebhook(
            mac = viewModel.macAddress.value,
            key = viewModel.authKey.value,
            webhook = viewModel.webhookUrl.value
        )
        result.fold(
            onSuccess = { (mac, authKey, webhookUrl) ->
                val serviceIntent = Intent(this, BleSyncService::class.java).apply {
                    action = BleSyncService.ACTION_START_SYNC
                    putExtra(BleSyncService.EXTRA_MAC_ADDRESS, mac)
                    putExtra(BleSyncService.EXTRA_AUTH_KEY, authKey)
                    putExtra(BleSyncService.EXTRA_WEBHOOK_URL, webhookUrl)
                }
                ContextCompat.startForegroundService(this, serviceIntent)
            },
            onFailure = { error ->
                Toast.makeText(this, error.message, Toast.LENGTH_LONG).show()
            }
        )
    }

    private fun triggerStartHeartRate() {
        val service = bleService
        if (service != null) {
            val nowStreaming = service.toggleHeartRateStream()
            val msg = if (nowStreaming) {
                "Eco HR Stream aktif (Otomatis matikan sensor tiap dapat BPM)"
            } else {
                "Stream dihentikan & sensor optik jam dimatikan"
            }
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
        } else {
            val intent = Intent(this, BleSyncService::class.java).apply {
                action = BleSyncService.ACTION_TOGGLE_HEART_RATE
            }
            startService(intent)
        }
    }

    private fun triggerSleepSync() {
        if (bleService != null) {
            val past24Hours = System.currentTimeMillis() - 24 * 3600 * 1000L
            bleService?.triggerSleepDataSync(past24Hours)
        } else {
            val intent = Intent(this, BleSyncService::class.java).apply {
                action = BleSyncService.ACTION_SYNC_SLEEP
            }
            startService(intent)
        }
        Toast.makeText(this, "Meminta data tidur dan aktivitas dari jam...", Toast.LENGTH_SHORT).show()
    }

    private fun triggerSyncAll() {
        triggerSleepSync()
        bleService?.let { service ->
            service.queryBatteryStatus()
            service.requestLiveHeartRateMeasurement()
        }
        Toast.makeText(this, "Menyinkronkan aktivitas, tidur, baterai & sampel HR...", Toast.LENGTH_SHORT).show()
    }

    private fun triggerWebhookExport() {
        val url = viewModel.webhookUrl.value
        if (bleService != null) {
            bleService?.triggerWebhookExport(url)
        } else {
            val intent = Intent(this, BleSyncService::class.java).apply {
                action = BleSyncService.ACTION_EXPORT_WEBHOOK
                putExtra(BleSyncService.EXTRA_WEBHOOK_URL, url)
            }
            startService(intent)
        }
        Toast.makeText(this, "Mengirim payload ke webhook...", Toast.LENGTH_SHORT).show()
    }

    private fun stopBleService() {
        val stopIntent = Intent(this, BleSyncService::class.java).apply {
            action = BleSyncService.ACTION_STOP_SYNC
        }
        startService(stopIntent)
        Toast.makeText(this, "Menghentikan BLE Service...", Toast.LENGTH_SHORT).show()
    }

    private fun showEditWebhookDialog() {
        val input = EditText(this).apply {
            setText(viewModel.webhookUrl.value)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setSingleLine(true)
            setPadding(32, 24, 32, 24)
        }

        AlertDialog.Builder(this)
            .setTitle("Ubah Webhook Dispatcher URL")
            .setView(input)
            .setPositiveButton("Simpan") { _, _ ->
                val newUrl = input.text.toString().trim()
                if (viewModel.saveWebhookUrl(newUrl)) {
                    Toast.makeText(this, "Webhook URL disimpan!", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, "URL Webhook tidak valid", Toast.LENGTH_LONG).show()
                }
            }
            .setNegativeButton("Batal", null)
            .show()
    }

    private fun showCredentialsSettingsDialog() {
        val density = resources.displayMetrics.density
        val pad = (20 * density).toInt()

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, pad / 2)
        }

        // Section: Notifikasi Pintar
        val notifSectionHeader = TextView(this).apply {
            text = "NOTIFIKASI PINTAR"
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.primary_mint))
            setPadding(0, 0, 0, (4 * density).toInt())
        }
        val cbAppNotifs = CheckBox(this).apply {
            text = "Teruskan Notifikasi Aplikasi (WhatsApp, SMS, dll)"
            isChecked = Rw3NotificationListenerService.isNotificationForwardingEnabled(this@MainActivity)
            setTextColor(Color.parseColor("#E2E8F0"))
            textSize = 13f
        }
        val cbCallNotifs = CheckBox(this).apply {
            text = "Peringatan Panggilan Masuk (Incoming Calls)"
            isChecked = Rw3NotificationListenerService.isCallForwardingEnabled(this@MainActivity)
            setTextColor(Color.parseColor("#E2E8F0"))
            textSize = 13f
        }
        val cbWhitelistMode = CheckBox(this).apply {
            text = "Gunakan Filter Whitelist (Hanya aplikasi terpilih)"
            isChecked = Rw3NotificationListenerService.isWhitelistModeEnabled(this@MainActivity)
            setTextColor(Color.parseColor("#E2E8F0"))
            textSize = 13f
        }

        val divider = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                (1 * density).toInt()
            ).apply {
                setMargins(0, (12 * density).toInt(), 0, (12 * density).toInt())
            }
            setBackgroundColor(Color.parseColor("#334155"))
        }

        // Section: Kredensial Jam
        val credSectionHeader = TextView(this).apply {
            text = "KREDENSIAL REDMI WATCH 3"
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.secondary_cyan))
            setPadding(0, 0, 0, (4 * density).toInt())
        }

        val macLabel = TextView(this).apply {
            text = "Redmi Watch 3 MAC Address:"
            textSize = 12f
            setTextColor(Color.parseColor("#94A3B8"))
        }
        val macEdit = EditText(this).apply {
            setText(viewModel.macAddress.value)
            filters = arrayOf(InputFilter.LengthFilter(17))
            setSingleLine(true)
        }

        val keyLabel = TextView(this).apply {
            text = "32-Character Hex Auth Key:"
            textSize = 12f
            setTextColor(Color.parseColor("#94A3B8"))
            setPadding(0, pad / 2, 0, 0)
        }
        val keyEdit = EditText(this).apply {
            setText(viewModel.authKey.value)
            filters = arrayOf(InputFilter.LengthFilter(34))
            setSingleLine(true)
        }

        val webhookLabel = TextView(this).apply {
            text = "Webhook Dispatcher URL:"
            textSize = 12f
            setTextColor(Color.parseColor("#94A3B8"))
            setPadding(0, pad / 2, 0, 0)
        }
        val webhookEdit = EditText(this).apply {
            setText(viewModel.webhookUrl.value)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setSingleLine(true)
        }

        layout.addView(notifSectionHeader)
        layout.addView(cbAppNotifs)
        layout.addView(cbCallNotifs)
        layout.addView(cbWhitelistMode)
        layout.addView(divider)
        layout.addView(credSectionHeader)
        layout.addView(macLabel)
        layout.addView(macEdit)
        layout.addView(keyLabel)
        layout.addView(keyEdit)
        layout.addView(webhookLabel)
        layout.addView(webhookEdit)

        AlertDialog.Builder(this)
            .setTitle("Pengaturan Sistem & Notifikasi")
            .setView(layout)
            .setPositiveButton("Simpan") { _, _ ->
                Rw3NotificationListenerService.setNotificationForwardingEnabled(this, cbAppNotifs.isChecked)
                Rw3NotificationListenerService.setCallForwardingEnabled(this, cbCallNotifs.isChecked)
                Rw3NotificationListenerService.setWhitelistModeEnabled(this, cbWhitelistMode.isChecked)
                updateNotificationUiState()

                val result = viewModel.saveCredentialsAndWebhook(
                    mac = macEdit.text.toString(),
                    key = keyEdit.text.toString(),
                    webhook = webhookEdit.text.toString()
                )
                result.fold(
                    onSuccess = {
                        Toast.makeText(this, "Pengaturan berhasil disimpan!", Toast.LENGTH_SHORT).show()
                    },
                    onFailure = { error ->
                        Toast.makeText(this, error.message, Toast.LENGTH_LONG).show()
                    }
                )
            }
            .setNegativeButton("Batal", null)
            .show()
    }

    private fun copyLogsToClipboard() {
        val text = binding.tvTerminalLogOutput.text.toString()
        copyTextToClipboard("RW3-BLE-Log", text)
        Toast.makeText(this, "Log disalin ke Clipboard!", Toast.LENGTH_SHORT).show()
    }

    private fun copyTextToClipboard(label: String, text: String) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText(label, text))
    }

    // =========================================================================
    // Notification Whitelist Filter Dialog & Adapter
    // =========================================================================

    private data class WhitelistAppItem(
        val appName: String,
        val packageName: String,
        val icon: Drawable?,
        var isSelected: Boolean
    )

    private class WhitelistAppAdapter(
        private val allItems: MutableList<WhitelistAppItem>,
        private val onSelectionChanged: () -> Unit
    ) : RecyclerView.Adapter<WhitelistAppAdapter.ViewHolder>() {

        private var filteredItems: MutableList<WhitelistAppItem> = allItems.toMutableList()

        class ViewHolder(val binding: ItemWhitelistAppBinding) : RecyclerView.ViewHolder(binding.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val inflater = LayoutInflater.from(parent.context)
            val binding = ItemWhitelistAppBinding.inflate(inflater, parent, false)
            return ViewHolder(binding)
        }

        override fun getItemCount(): Int = filteredItems.size

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val item = filteredItems[position]
            holder.binding.tvAppName.text = item.appName
            holder.binding.tvPackageName.text = item.packageName
            if (item.icon != null) {
                holder.binding.ivAppIcon.setImageDrawable(item.icon)
            } else {
                holder.binding.ivAppIcon.setImageResource(R.drawable.ic_hub)
            }

            // Unbind listener before updating isChecked to prevent circular recycling glitches
            holder.binding.cbWhitelisted.setOnCheckedChangeListener(null)
            holder.binding.cbWhitelisted.isChecked = item.isSelected

            holder.binding.cbWhitelisted.setOnCheckedChangeListener { _, isChecked ->
                item.isSelected = isChecked
                onSelectionChanged()
            }

            holder.binding.root.setOnClickListener {
                item.isSelected = !item.isSelected
                holder.binding.cbWhitelisted.isChecked = item.isSelected
                onSelectionChanged()
            }
        }

        @SuppressLint("NotifyDataSetChanged")
        fun filter(query: String) {
            val q = query.trim().lowercase(Locale.ROOT)
            filteredItems = if (q.isEmpty()) {
                allItems.toMutableList()
            } else {
                allItems.filter {
                    it.appName.lowercase(Locale.ROOT).contains(q) ||
                    it.packageName.lowercase(Locale.ROOT).contains(q)
                }.toMutableList()
            }
            notifyDataSetChanged()
        }

        @SuppressLint("NotifyDataSetChanged")
        fun selectPresets(presetPackages: Set<String>) {
            for (item in allItems) {
                if (presetPackages.contains(item.packageName)) {
                    item.isSelected = true
                }
            }
            notifyDataSetChanged()
            onSelectionChanged()
        }

        @SuppressLint("NotifyDataSetChanged")
        fun selectAll(select: Boolean) {
            for (item in allItems) {
                item.isSelected = select
            }
            notifyDataSetChanged()
            onSelectionChanged()
        }

        fun getSelectedPackages(): Set<String> {
            return allItems.filter { it.isSelected }.map { it.packageName }.toSet()
        }
    }

    private fun showAppWhitelistDialog() {
        val dialogBinding = DialogAppWhitelistBinding.inflate(layoutInflater)
        val initialWhitelist = HashSet(Rw3NotificationListenerService.getWhitelistedPackages(this))
        val isWhitelistMode = Rw3NotificationListenerService.isWhitelistModeEnabled(this)

        dialogBinding.switchWhitelistMode.isChecked = isWhitelistMode
        dialogBinding.tvSwitchHint.text = if (isWhitelistMode) {
            "Hanya aplikasi tercentang yang diteruskan ke jam"
        } else {
            "Filter nonaktif: SEMUA aplikasi diteruskan ke jam"
        }

        dialogBinding.switchWhitelistMode.setOnCheckedChangeListener { _, isChecked ->
            dialogBinding.tvSwitchHint.text = if (isChecked) {
                "Hanya aplikasi tercentang yang diteruskan ke jam"
            } else {
                "Filter nonaktif: SEMUA aplikasi diteruskan ke jam"
            }
        }

        var adapter: WhitelistAppAdapter? = null

        fun updateCountDisplay() {
            val totalSelected = adapter?.getSelectedPackages()?.size ?: initialWhitelist.size
            val showingCount = adapter?.itemCount ?: 0
            dialogBinding.tvSelectedCount.text = "$totalSelected aplikasi diizinkan ($showingCount ditampilkan)"
        }

        dialogBinding.rvApps.layoutManager = LinearLayoutManager(this)

        var scanJob: Job? = null

        val alertDialog = AlertDialog.Builder(this)
            .setView(dialogBinding.root)
            .setPositiveButton("Simpan") { _, _ ->
                val selected = adapter?.getSelectedPackages() ?: initialWhitelist
                val whitelistActive = dialogBinding.switchWhitelistMode.isChecked
                Rw3NotificationListenerService.setWhitelistModeEnabled(this, whitelistActive)
                Rw3NotificationListenerService.setWhitelistedPackages(this, selected)
                updateNotificationUiState()
                Toast.makeText(
                    this,
                    if (whitelistActive) {
                        "Filter Whitelist Aktif: ${selected.size} aplikasi diizinkan."
                    } else {
                        "Filter Whitelist dinonaktifkan (semua aplikasi diizinkan)."
                    },
                    Toast.LENGTH_SHORT
                ).show()
            }
            .setNegativeButton("Batal", null)
            .setOnDismissListener {
                scanJob?.cancel()
            }
            .create()

        alertDialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        alertDialog.show()

        // Asynchronously scan installed applications without blocking UI
        scanJob = lifecycleScope.launch(Dispatchers.Default) {
            val pm = packageManager
            val mainIntent = Intent(Intent.ACTION_MAIN, null).apply {
                addCategory(Intent.CATEGORY_LAUNCHER)
            }
            val resolveInfos = pm.queryIntentActivities(mainIntent, 0)
            val seen = HashSet<String>()
            val items = mutableListOf<WhitelistAppItem>()

            for (ri in resolveInfos) {
                val pkg = ri.activityInfo.packageName
                if (pkg != packageName && seen.add(pkg)) {
                    val label = ri.loadLabel(pm).toString()
                    val icon = try { ri.loadIcon(pm) } catch (_: Exception) { null }
                    items.add(WhitelistAppItem(label, pkg, icon, initialWhitelist.contains(pkg)))
                }
            }

            // Also include any currently whitelisted / default packages (e.g. system messaging/dialer)
            val extraPackages = initialWhitelist + Rw3NotificationListenerService.DEFAULT_WHITELIST
            for (pkg in extraPackages) {
                if (pkg != packageName && seen.add(pkg)) {
                    val (label, icon) = try {
                        val info = pm.getApplicationInfo(pkg, 0)
                        Pair(pm.getApplicationLabel(info).toString(), pm.getApplicationIcon(info))
                    } catch (_: Exception) {
                        Pair(pkg.substringAfterLast('.'), null)
                    }
                    items.add(WhitelistAppItem(label, pkg, icon, initialWhitelist.contains(pkg)))
                }
            }

            // Sort: Selected apps first, then alphabetically by name
            items.sortWith(
                compareByDescending<WhitelistAppItem> { it.isSelected }
                    .thenBy { it.appName.lowercase(Locale.ROOT) }
            )

            withContext(Dispatchers.Main) {
                adapter = WhitelistAppAdapter(items) {
                    updateCountDisplay()
                }
                dialogBinding.rvApps.adapter = adapter
                dialogBinding.layoutLoading.visibility = View.GONE
                dialogBinding.rvApps.visibility = View.VISIBLE
                updateCountDisplay()

                dialogBinding.etSearchApp.addTextChangedListener(object : TextWatcher {
                    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                        adapter?.filter(s?.toString() ?: "")
                        updateCountDisplay()
                    }
                    override fun afterTextChanged(s: Editable?) {}
                })

                dialogBinding.btnPresetChat.setOnClickListener {
                    adapter?.selectPresets(Rw3NotificationListenerService.DEFAULT_WHITELIST)
                }

                dialogBinding.btnSelectAll.setOnClickListener {
                    adapter?.selectAll(true)
                }

                dialogBinding.btnClearAll.setOnClickListener {
                    adapter?.selectAll(false)
                }
            }
        }
    }
}
