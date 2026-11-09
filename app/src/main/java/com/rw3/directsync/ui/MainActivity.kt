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
import com.rw3.directsync.data.db.entity.SleepSessionEntity
import com.rw3.directsync.sync.*
import com.rw3.directsync.databinding.DialogSyncCenterBinding
import com.rw3.directsync.databinding.ItemSyncMetricBinding
import com.rw3.directsync.databinding.ItemMiFitnessHandoffBinding
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.rw3.directsync.data.HealthConnectSleepImporter
import com.rw3.directsync.databinding.ActivityMainBinding
import com.rw3.directsync.databinding.DialogAppWhitelistBinding
import com.rw3.directsync.databinding.ItemWhitelistAppBinding
import com.rw3.directsync.databinding.ItemWorkoutHistoryBinding
import com.rw3.directsync.databinding.ItemSleepHistoryBinding
import com.rw3.directsync.databinding.DialogSleepHistoryBinding
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
        TERMINAL,
        SETTINGS
    }

    private val viewModel: MainViewModel by viewModels()
    private lateinit var binding: ActivityMainBinding

    private var bleService: BleSyncService? = null
    private var serviceStateJob: Job? = null
    private var pendingActionAfterPermission: (() -> Unit)? = null
    private val syncStatuses by lazy { SyncStatusRepository.get(this) }
    private val connectionMode by lazy { DirectSyncMode.get(this) }
    private var syncCenterDialog: BottomSheetDialog? = null
    private var syncCenterBinding: DialogSyncCenterBinding? = null
    private val syncRows = mutableMapOf<SyncMetric, ItemSyncMetricBinding>()
    private var sleepImportRunning = false
    private var sleepHistoryRecords = emptyList<SleepSessionEntity>()
    private val sleepPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) importSleepHistory() else {
            binding.tvSleepImportStatus.text = "Izin membaca tidur belum diberikan. Data yang sudah tersimpan tetap tersedia."
        }
    }

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
            service.autoConnectFromSavedPrefsIfIdle()
            service.refreshHistoryIfStale()
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
                    launch {
                        service.currentMediaState.collect { media ->
                            updateMediaUi(media)
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
        bleService?.refreshHistoryIfStale()
        binding.root.post {
            if (!isFinishing && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) && connectionMode.consumeReturnPrompt()) {
                AlertDialog.Builder(this).setTitle("Lanjutkan DirectSync?")
                    .setMessage("Selesai menggunakan Mi Fitness? DirectSync akan menghubungkan jam kembali setelah Anda memilih Lanjutkan.")
                    .setPositiveButton("Lanjutkan") { _, _ -> resumeDirectSync() }
                    .setNegativeButton("Tetap dijeda", null).show()
            }
        }
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

    override fun onDestroy() {
        syncCenterDialog?.dismiss()
        super.onDestroy()
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
        binding.btnOpenSyncCenter.setOnClickListener { showSyncCenter() }
        setupHandoff(binding.miFitnessHandoff)
        binding.btnViewSleepHistory.setOnClickListener { showAllSleepHistory() }
        binding.btnSleepInsights.setOnClickListener {
            startActivity(Intent(this, SleepInsightsActivity::class.java))
        }
        binding.btnImportSleepHistory.setOnClickListener {
            binding.tvSleepImportStatus.visibility = View.VISIBLE
            if (Build.VERSION.SDK_INT < 34) {
                binding.tvSleepImportStatus.text = "Impor Mi Fitness tersedia pada Android 14 atau lebih baru. Sinkronisasi langsung dari jam tetap tersedia."
            } else if (ContextCompat.checkSelfPermission(this, HealthConnectSleepImporter.READ_SLEEP) == PackageManager.PERMISSION_GRANTED) {
                importSleepHistory()
            } else sleepPermissionLauncher.launch(HealthConnectSleepImporter.READ_SLEEP)
        }
        binding.btnDashboardConnect.setOnClickListener {
            ensurePermissionsAndRun { validateAndStartBleService() }
        }
        binding.btnHr24h.setOnClickListener { viewModel.heartRateWindowHours.value = 24 }
        binding.btnHr7d.setOnClickListener { viewModel.heartRateWindowHours.value = 168 }
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

        // Card 1C: Two-Way Media & Music Controls
        binding.btnMediaPlayPause.setOnClickListener {
            bleService?.let { svc ->
                if (svc.currentMediaState.value.isPlaying) {
                    svc.sendMediaPause()
                } else {
                    svc.sendMediaPlay()
                }
            } ?: Toast.makeText(this, "Hubungkan jam tangan atau putar musik di HP terlebih dahulu.", Toast.LENGTH_SHORT).show()
        }

        binding.btnMediaNext.setOnClickListener {
            bleService?.sendMediaNext() ?: Toast.makeText(this, "Hubungkan jam tangan atau putar musik di HP terlebih dahulu.", Toast.LENGTH_SHORT).show()
        }

        binding.btnMediaPrev.setOnClickListener {
            bleService?.sendMediaPrevious() ?: Toast.makeText(this, "Hubungkan jam tangan atau putar musik di HP terlebih dahulu.", Toast.LENGTH_SHORT).show()
        }

        // Card Watchface & Wallpaper
        binding.cardWatchfaceWallpaper.setOnClickListener {
            startActivity(Intent(this, WatchfaceActivity::class.java))
        }
        binding.btnOpenWatchfaceManager.setOnClickListener {
            startActivity(Intent(this, WatchfaceActivity::class.java))
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
            selectTab(NavTab.SETTINGS)
        }
    }

    private fun selectTab(tab: NavTab) {
        val mint = ContextCompat.getColor(this, R.color.primary_mint)
        val muted = ContextCompat.getColor(this, R.color.on_surface_variant)

        val settings = tab == NavTab.SETTINGS
        binding.layoutDashboardContainer.requestFocus()
        binding.layoutOverviewHeading.visibility = if (tab == NavTab.DASHBOARD) View.VISIBLE else View.GONE
        binding.btnOpenSyncCenter.visibility = if (tab == NavTab.DASHBOARD) View.VISIBLE else View.GONE
        binding.miFitnessHandoff.root.visibility = if (settings) View.VISIBLE else View.GONE
        listOf(binding.cardDeviceIdentity, binding.cardSmartNotifications,
            binding.cardMediaAndAutoReconnect, binding.cardWatchfaceWallpaper, binding.cardTelemetryPipeline).forEach {
            it.visibility = if (settings) View.VISIBLE else View.GONE
        }
        binding.cardHeartRateStream.visibility = if (tab == NavTab.DASHBOARD) View.VISIBLE else View.GONE
        binding.cardSleepArchitecture.visibility = if (tab == NavTab.DASHBOARD) View.VISIBLE else View.GONE
        binding.cardSleepHistory.visibility = if (tab == NavTab.DASHBOARD) View.VISIBLE else View.GONE
        listOf(binding.cardDailyActivity, binding.cardHealthCheckups, binding.cardWorkoutHistory).forEach {
            it.visibility = if (settings) View.GONE else View.VISIBLE
        }
        binding.imgNavSettings.imageTintList = ColorStateList.valueOf(if (settings) mint else muted)
        binding.tvNavSettings.setTextColor(if (settings) mint else muted)

        when (tab) {
            NavTab.SETTINGS -> {
                binding.layoutDashboardContainer.visibility = View.VISIBLE
                binding.layoutTerminalContainer.visibility = View.GONE
                binding.tvHeaderAppTitle.text = "Pengaturan"
                listOf(binding.imgNavDash, binding.imgNavActivity, binding.imgNavTerminal).forEach {
                    it.imageTintList = ColorStateList.valueOf(muted)
                }
                listOf(binding.tvNavDash, binding.tvNavActivity, binding.tvNavTerminal).forEach { it.setTextColor(muted) }
                binding.layoutDashboardContainer.post { binding.layoutDashboardContainer.scrollTo(0, 0) }
            }
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

                binding.layoutDashboardContainer.post { binding.layoutDashboardContainer.scrollTo(0, 0) }
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
                launch { syncStatuses.entries.collect { renderSyncCenter() } }
                launch { viewModel.syncMetricEvidence.collect { renderSyncCenter() } }
                launch { connectionMode.paused.collect { updateUiForSyncState(viewModel.syncState.value) } }
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

                // Use persisted timestamps; cached live state must not create new chart points.
                launch {
                    viewModel.latestHeartRate.collect { record ->
                        binding.tvLiveBpmValue.text = record?.bpm?.toString() ?: "—"
                        binding.tvHrLastUpdate.text = record?.let { "Terakhir · ${metricTime(it.timestamp)}" } ?: "Belum ada pengukuran"
                    }
                }
                launch {
                    viewModel.heartRateWindowHours.collect { hours ->
                        binding.sparklineWaveView.setWindowHours(hours)
                        binding.tvHrChartPeriod.text = "${if (hours == 24) "24 jam · rata-rata 5 menit" else "7 hari · rata-rata 30 menit"}\nSentuh grafik untuk nilai sampel asli"
                        binding.btnHr24h.setTextColor(ContextCompat.getColor(this@MainActivity, if (hours == 24) R.color.primary_mint else R.color.on_surface_variant))
                        binding.btnHr7d.setTextColor(ContextCompat.getColor(this@MainActivity, if (hours == 168) R.color.primary_mint else R.color.on_surface_variant))
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
                            binding.tvHrMinValue.text = stats.minBpm?.toString() ?: "—"
                            binding.tvHrAvgValue.text = stats.avgBpm?.toString() ?: "—"
                            binding.tvHrMaxValue.text = stats.maxBpm?.toString() ?: "—"
                            binding.tvHr24hStats.text = "${numberFormat.format(stats.count)} sampel dalam periode ini"
                        } else {
                            binding.tvHrMinValue.text = "—"
                            binding.tvHrAvgValue.text = "—"
                            binding.tvHrMaxValue.text = "—"
                            binding.tvHr24hStats.text = "Belum ada sampel dalam periode ini"
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
                            binding.tvDailyStepsValue.text = record.steps?.let { numberFormat.format(it) } ?: "—"
                            binding.tvDailyCaloriesValue.text = record.calories?.let { numberFormat.format(it) } ?: "—"
                            binding.tvDailyActivityDate.text = java.text.SimpleDateFormat("dd MMM yyyy", Locale.getDefault()).format(java.util.Date(record.timestamp))
                            binding.tvRestingHrValue.text = record.restingHeartRate?.let { "$it BPM" } ?: "—"
                            binding.tvStandHoursValue.text = record.standingHours?.let { "$it jam" } ?: "—"
                            binding.tvHrRangeValue.text = "${record.averageHeartRate ?: "—"} / ${record.minHeartRate ?: "—"} / ${record.maxHeartRate ?: "—"}"

                            val stepsPct = ((steps.toFloat() / 6000f) * 100f).coerceIn(0f, 100f)
                            val calsPct = ((cals.toFloat() / 300f) * 100f).coerceIn(0f, 100f)

                            binding.tvDailyStepsPercent.text = if (record.steps != null) "${stepsPct.toInt()}% tercapai" else "Belum ada data"
                            binding.tvDailyCaloriesPercent.text = if (record.calories != null) "${calsPct.toInt()}% tercapai" else "Belum ada data"

                            binding.gaugeDailySteps.progress = stepsPct
                            binding.gaugeDailyCalories.progress = calsPct

                        } else {
                            binding.gaugeDailySteps.progress = 0f
                            binding.gaugeDailyCalories.progress = 0f
                            binding.tvDailyActivityDate.text = "Belum ada data"
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
                        binding.layoutWorkoutHistoryList.removeAllViews()
                        binding.tvWorkoutSummary.text = if (records.isEmpty()) "Belum ada sesi tersimpan" else "${records.size} sesi tersimpan · terbaru lebih dulu"
                        if (records.isNotEmpty()) {
                            records.forEach { record ->
                                addWorkoutRowView(record)
                            }
                        } else {
                            binding.layoutWorkoutHistoryList.addView(TextView(this@MainActivity).apply {
                                text = "Belum ada sesi olahraga. Sinkronkan riwayat dari jam."
                                textSize = 14f
                                setTextColor(ContextCompat.getColor(this@MainActivity, R.color.on_surface_variant))
                            })
                        }
                    }
                }

                // 11. Card 6: Arsitektur Tidur Summary
                launch {
                    viewModel.sleepSummary.collect { summary ->
                        updateSleepSummaryUi(summary)
                    }
                }
                launch { viewModel.sleepHistory.collect { updateSleepHistoryUi(it) } }
            }
        }
    }

    private fun addWorkoutRowView(record: ActivityRecordEntity) {
        val item = ItemWorkoutHistoryBinding.inflate(layoutInflater, binding.layoutWorkoutHistoryList, false)
        item.workoutTitle.text = sportName(record.sportType)
        item.workoutDate.text = java.text.SimpleDateFormat("EEEE, dd MMM yyyy", Locale("id", "ID"))
            .format(java.util.Date(record.timestamp))
        val clock = java.text.SimpleDateFormat("HH:mm", Locale.getDefault())
        val start = clock.format(java.util.Date(record.timestamp))
        val end = record.endTimestamp?.let { clock.format(java.util.Date(it)) }
        item.workoutPeriod.text = if (end != null) "$start – $end · Selesai" else "Mulai $start"
        item.workoutDuration.text = record.activeSeconds?.let { seconds ->
            val hours = seconds / 3600
            val minutes = seconds % 3600 / 60
            if (hours > 0) String.format(Locale.getDefault(), "%d:%02d:%02d", hours, minutes, seconds % 60)
            else String.format(Locale.getDefault(), "%d:%02d", minutes, seconds % 60)
        } ?: "—"
        item.workoutDurationLabel.text = if ((record.activeSeconds ?: 0) >= 3600) "Durasi · jam:mnt:dtk" else "Durasi · mnt:dtk"
        item.workoutCalories.text = record.calories?.let { numberFormat.format(it) } ?: "—"
        item.workoutHrMin.text = record.minHeartRate?.toString() ?: "—"
        item.workoutHrAverage.text = record.averageHeartRate?.toString() ?: "—"
        item.workoutHrMax.text = record.maxHeartRate?.toString() ?: "—"
        item.workoutIcon.setImageResource(when (record.sportType) {
            1 -> R.drawable.ic_directions_run
            2, 15 -> R.drawable.ic_nordic_walking
            6, 7 -> R.drawable.ic_pedal_bike
            else -> R.drawable.ic_fitness_center
        })
        binding.layoutWorkoutHistoryList.addView(item.root)
    }

    private fun importSleepHistory() {
        if (Build.VERSION.SDK_INT < 34 || sleepImportRunning) return
        sleepImportRunning = true
        renderSyncCenter()
        binding.btnImportSleepHistory.isEnabled = false
        binding.tvSleepImportStatus.visibility = View.VISIBLE
        binding.tvSleepImportStatus.text = "Mengambil riwayat tidur Mi Fitness…"
        lifecycleScope.launch {
            try {
                val count = HealthConnectSleepImporter(this@MainActivity).importHistory()
                binding.tvSleepImportStatus.text = if (count > 0) "$count sesi Mi Fitness dibaca. Riwayat tersimpan di HP."
                else "Belum ada tidur Mi Fitness di Health Connect. Sinkronkan tidur di Mi Fitness, lalu coba kembali."
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) {
                syncStatuses.importFailed(e.message ?: "Periksa izin Health Connect.")
                binding.tvSleepImportStatus.text = "Riwayat belum dapat dibaca: ${e.message ?: "periksa izin Health Connect"}"
            } finally {
                sleepImportRunning = false
                binding.btnImportSleepHistory.isEnabled = true
                renderSyncCenter()
            }
        }
    }

    private fun formatSleepMinutes(minutes: Int): String =
        if (minutes >= 60) "${minutes / 60}j ${minutes % 60}m" else "${minutes}m"

    private fun bindSleepRow(item: ItemSleepHistoryBinding, session: SleepSessionEntity) {
        item.sleepDate.text = java.text.SimpleDateFormat("EEE, dd MMM", Locale("id", "ID"))
            .format(java.util.Date(session.endTimestamp))
        val clock = java.text.SimpleDateFormat("HH:mm", Locale.getDefault())
        item.sleepTime.text = "${clock.format(java.util.Date(session.startTimestamp))} – ${clock.format(java.util.Date(session.endTimestamp))}"
        item.sleepDuration.text = formatSleepMinutes(session.totalMinutes)
        item.sleepPhaseBar.setPhases(session.deepMinutes.takeIf { session.hasStageData },
            session.lightMinutes.takeIf { session.hasStageData }, session.remMinutes.takeIf { session.hasStageData })
        item.sleepPhaseCaption.text = if (session.hasStageData)
            "Deep ${session.deepMinutes}m · Light ${session.lightMinutes}m · REM ${session.remMinutes}m"
        else "Rincian fase belum tersedia"
        item.root.setOnClickListener { showSleepDetails(session) }
    }

    private fun updateSleepHistoryUi(records: List<SleepSessionEntity>) {
        sleepHistoryRecords = records
        val zone = java.time.ZoneId.systemDefault()
        val today = java.time.LocalDate.now(zone)
        val week = records.filter {
            java.time.Instant.ofEpochMilli(it.endTimestamp).atZone(zone).toLocalDate() in today.minusDays(6)..today
        }
        val average = week.takeIf { it.isNotEmpty() }?.map { it.totalMinutes }?.average()?.toInt()
        binding.tvSleepHistorySummary.text = if (records.isEmpty()) "Belum ada sesi tidur tersimpan"
        else average?.let { "7 hari · ${week.size} sesi · rata-rata ${formatSleepMinutes(it)}" } ?: "${records.size} sesi tersimpan"
        binding.layoutSleepHistoryList.removeAllViews()
        records.take(3).forEach { session ->
            val item = ItemSleepHistoryBinding.inflate(layoutInflater, binding.layoutSleepHistoryList, false)
            bindSleepRow(item, session)
            binding.layoutSleepHistoryList.addView(item.root)
        }
        binding.btnViewSleepHistory.text = "Lihat semua (${records.size})"
        binding.btnViewSleepHistory.isEnabled = records.isNotEmpty()
    }

    private class SleepHistoryHolder(val item: ItemSleepHistoryBinding) : RecyclerView.ViewHolder(item.root)

    private fun showAllSleepHistory() {
        val records = sleepHistoryRecords.toList()
        if (records.isEmpty()) return
        val content = DialogSleepHistoryBinding.inflate(layoutInflater)
        val dialog = com.google.android.material.bottomsheet.BottomSheetDialog(this)
        val pageSize = 7
        var page = 0
        val pages = (records.size + pageSize - 1) / pageSize
        var visible = records.take(pageSize)
        val adapter = object : RecyclerView.Adapter<SleepHistoryHolder>() {
            override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = SleepHistoryHolder(
                ItemSleepHistoryBinding.inflate(layoutInflater, parent, false))
            override fun getItemCount() = visible.size
            override fun onBindViewHolder(holder: SleepHistoryHolder, position: Int) = bindSleepRow(holder.item, visible[position])
        }
        content.historyList.layoutManager = LinearLayoutManager(this)
        content.historyList.adapter = adapter
        fun renderPage() {
            visible = records.drop(page * pageSize).take(pageSize)
            adapter.notifyDataSetChanged()
            content.historyList.scrollToPosition(0)
            content.historyCount.text = "${records.size} sesi tersimpan · ketuk kartu untuk detail"
            content.pageNumber.text = "${page + 1} / $pages"
            content.previousPage.isEnabled = page > 0
            content.nextPage.isEnabled = page < pages - 1
        }
        content.previousPage.setOnClickListener { if (page > 0) { page--; renderPage() } }
        content.nextPage.setOnClickListener { if (page < pages - 1) { page++; renderPage() } }
        dialog.setContentView(content.root)
        dialog.setOnShowListener {
            dialog.findViewById<FrameLayout>(com.google.android.material.R.id.design_bottom_sheet)?.let { sheet ->
                sheet.layoutParams.height = (resources.displayMetrics.heightPixels * 0.82f).toInt()
                com.google.android.material.bottomsheet.BottomSheetBehavior.from(sheet).state =
                    com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED
            }
        }
        renderPage()
        dialog.show()
    }

    private fun showSleepDetails(session: SleepSessionEntity) {
        startActivity(Intent(this, SleepInsightsActivity::class.java)
            .putExtra(SleepInsightsActivity.EXTRA_SESSION_START, session.startTimestamp))
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
        binding.tvTotalSleepValue.text = if (summary.hasData) formatSleepMinutes(summary.totalMinutes) else "—"
        binding.tvSleepPeriod.text = if (summary.hasData)
            "${metricTime(summary.startTimestamp)} – ${metricTime(summary.endTimestamp)}" else "Belum ada sesi tidur tersimpan"
        binding.tvSleepSource.text = if (summary.sourceFileId.startsWith("healthconnect:")) "Mi Fitness" else "Jam"
        binding.sleepPhaseBar.setPhases(summary.deepMinutes.takeIf { summary.hasData && summary.hasStageData },
            summary.lightMinutes.takeIf { summary.hasData && summary.hasStageData },
            summary.remMinutes.takeIf { summary.hasData && summary.hasStageData })
        binding.tvLegendSleepDeep.text = "● Deep\n${if (summary.hasStageData) formatSleepMinutes(summary.deepMinutes) else "—"}"
        binding.tvLegendSleepLight.text = "● Light\n${if (summary.hasStageData) formatSleepMinutes(summary.lightMinutes) else "—"}"
        binding.tvLegendSleepRem.text = "● REM\n${if (summary.hasStageData) formatSleepMinutes(summary.remMinutes) else "—"}"
        binding.tvSleepPhaseStatus.text = if (summary.hasStageData) "Proporsi fase selama tidur"
            else "Rincian Deep, Light, dan REM belum tersedia untuk sesi ini."
        binding.tvTotalSleepValue.contentDescription = "Total tidur ${summary.totalMinutes / 60} jam ${summary.totalMinutes % 60} menit"
    }

    @SuppressLint("SetTextI18n")
    private fun updateUiForSyncState(state: BleSyncService.SyncState) {
        val mintColor = ContextCompat.getColor(this, R.color.primary_mint)
        val cyanColor = ContextCompat.getColor(this, R.color.secondary_cyan)
        val coralColor = ContextCompat.getColor(this, R.color.heart_rate_coral)
        val mutedColor = ContextCompat.getColor(this, R.color.on_surface_variant)
        val connected = state is BleSyncService.SyncState.Authenticated ||
            state is BleSyncService.SyncState.StreamingHeartRate || state is BleSyncService.SyncState.SyncingSleep ||
            state is BleSyncService.SyncState.SleepSyncCompleted || state is BleSyncService.SyncState.WebhookDispatched
        binding.btnSyncAll.isEnabled = connected && state !is BleSyncService.SyncState.SyncingSleep
        binding.btnSyncAll.alpha = if (binding.btnSyncAll.isEnabled) 1f else 0.5f
        binding.btnSyncAll.text = if (state is BleSyncService.SyncState.SyncingSleep) "Mengambil riwayat…" else "Sinkronkan riwayat"
        binding.btnToggleHeartRate.isEnabled = connected
        binding.btnToggleHeartRate.alpha = if (connected) 1f else 0.5f
        binding.btnDashboardConnect.text = if (connected) "Jam terhubung" else "Hubungkan jam"
        binding.btnDashboardConnect.isEnabled = !connected

        when (state) {
            is BleSyncService.SyncState.Idle -> {
                binding.tvHeaderGattStatus.text = " Belum terhubung"
                binding.tvHeaderGattStatus.setTextColor(mutedColor)
                binding.imgHeaderGattDot.setImageResource(R.drawable.dot_pulse_coral)
                binding.tvDeviceStatusTag.text = "○ Idle"
                binding.tvDeviceStatusTag.setTextColor(mutedColor)
            }

            is BleSyncService.SyncState.Connecting -> {
                binding.tvHeaderGattStatus.text = " Menghubungkan…"
                binding.tvHeaderGattStatus.setTextColor(cyanColor)
                binding.imgHeaderGattDot.setImageResource(R.drawable.dot_pulse_green)
                binding.tvDeviceStatusTag.text = "⏳ Connecting..."
                binding.tvDeviceStatusTag.setTextColor(cyanColor)
            }

            is BleSyncService.SyncState.DiscoveringServices -> {
                binding.tvHeaderGattStatus.text = " Menyiapkan koneksi…"
                binding.tvHeaderGattStatus.setTextColor(cyanColor)
                binding.tvDeviceStatusTag.text = "⚡ Discovering"
                binding.tvDeviceStatusTag.setTextColor(cyanColor)
            }

            is BleSyncService.SyncState.Authenticating -> {
                binding.tvHeaderGattStatus.text = " Memverifikasi jam…"
                binding.tvHeaderGattStatus.setTextColor(cyanColor)
                binding.tvDeviceStatusTag.text = "🔑 Authenticating"
                binding.tvDeviceStatusTag.setTextColor(cyanColor)
            }

            is BleSyncService.SyncState.Authenticated -> {
                binding.tvHeaderGattStatus.text = " Terhubung"
                binding.tvHeaderGattStatus.setTextColor(mintColor)
                binding.imgHeaderGattDot.setImageResource(R.drawable.dot_pulse_green)
                binding.tvDeviceStatusTag.text = " Tersambung"
                binding.tvDeviceStatusTag.setTextColor(mintColor)
                binding.tvAutoReconnectStatus.text = "Tersambung (Eco Mode aktif • Siap auto-reconnect)"
            }

            is BleSyncService.SyncState.StreamingHeartRate -> {
                binding.tvHeaderGattStatus.text = " HR: ${state.latestBpm} BPM"
                binding.tvHeaderGattStatus.setTextColor(coralColor)
                binding.imgHeaderGattDot.setImageResource(R.drawable.dot_pulse_green)
                binding.tvDeviceStatusTag.text = " Tersambung"
                binding.tvDeviceStatusTag.setTextColor(mintColor)
            }

            is BleSyncService.SyncState.SyncingSleep -> {
                binding.tvHeaderGattStatus.text = " Mengambil riwayat…"
                binding.tvHeaderGattStatus.setTextColor(cyanColor)
                binding.tvDeviceStatusTag.text = "🌙 Syncing (${state.bytesBuffered} B)"
                binding.tvDeviceStatusTag.setTextColor(cyanColor)
            }

            is BleSyncService.SyncState.SleepSyncCompleted -> {
                binding.tvHeaderGattStatus.text = " Terhubung"
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
                binding.tvHeaderGattStatus.text = " Terputus"
                binding.tvHeaderGattStatus.setTextColor(coralColor)
                binding.imgHeaderGattDot.setImageResource(R.drawable.dot_pulse_coral)
                binding.tvDeviceStatusTag.text = "✕ Terputus"
                binding.tvDeviceStatusTag.setTextColor(coralColor)
                binding.tvAutoReconnectStatus.text = if (state.willReconnect) "Auto-Reconnect: ${state.reason}" else "Terputus: ${state.reason}"
            }

            is BleSyncService.SyncState.Error -> {
                binding.tvHeaderGattStatus.text = " Koneksi bermasalah"
                binding.tvHeaderGattStatus.setTextColor(coralColor)
                binding.imgHeaderGattDot.setImageResource(R.drawable.dot_pulse_coral)
                binding.tvDeviceStatusTag.text = "⚠ Error"
                binding.tvDeviceStatusTag.setTextColor(coralColor)
                binding.tvAutoReconnectStatus.text = "Error: ${state.message}"
            }
        }
        if (connectionMode.paused.value) {
            binding.tvHeaderGattStatus.text = " Dijeda · Mi Fitness"
            binding.tvDeviceStatusTag.text = "Jeda Mi Fitness"
            binding.btnDashboardConnect.text = "Lanjutkan DirectSync"
            binding.btnSyncAll.isEnabled = false
            binding.btnToggleHeartRate.isEnabled = false
            binding.tvAutoReconnectStatus.text = "DirectSync dijeda sampai Anda memilih Lanjutkan."
        }
        binding.btnConnectMain.text = if (connectionMode.paused.value) "Lanjutkan" else "Hubungkan"
        renderSyncCenter()
    }

    @SuppressLint("SetTextI18n")
    private fun updateMediaUi(media: BleSyncService.MediaPlaybackInfo) {
        val hasTrack = media.title.isNotEmpty()
        binding.tvMediaTrackTitle.text = if (hasTrack) media.title else "Tidak ada media yang diputar"
        binding.tvMediaArtist.text = if (hasTrack && media.artist.isNotEmpty()) media.artist else "Buka Spotify / YouTube Music di HP"
        binding.tvMediaVolumePercent.text = "Vol ${media.volumePercent}%"

        if (media.isPlaying) {
            binding.tvMediaStatusBadge.text = "Memutar"
            binding.tvMediaStatusBadge.setTextColor(ContextCompat.getColor(this, R.color.primary_mint))
            binding.btnMediaPlayPause.text = "⏸ Pause"
        } else {
            binding.tvMediaStatusBadge.text = if (hasTrack) "Jeda" else "Siap"
            binding.tvMediaStatusBadge.setTextColor(ContextCompat.getColor(this, R.color.secondary_cyan))
            binding.btnMediaPlayPause.text = "▶ Play"
        }
    }

    private fun setupHandoff(item: ItemMiFitnessHandoffBinding) {
        item.btnPauseMiFitness.setOnClickListener { openMiFitness() }
        item.btnResumeDirectSync.setOnClickListener { resumeDirectSync() }
    }

    private fun resumeDirectSync() {
        syncCenterDialog?.dismiss()
        ensurePermissionsAndRun { validateAndStartBleService() }
    }

    private fun openMiFitness() {
        val launchIntent = packageManager.getLaunchIntentForPackage("com.xiaomi.wearable")
        if (launchIntent == null) {
            Toast.makeText(this, "Mi Fitness belum terpasang di HP ini.", Toast.LENGTH_LONG).show()
            return
        }
        val wasPaused = connectionMode.paused.value
        val wasConnected = bleService?.watchfaceUploadReady?.value == true
        val service = bleService
        if (!wasPaused && service == null) {
            Toast.makeText(this, "Layanan sedang disiapkan. Coba sebentar lagi.", Toast.LENGTH_SHORT).show()
            return
        }
        if (!wasPaused && service?.pauseForMiFitness() != true) {
            Toast.makeText(this, "Selesaikan unggah watchface sebelum beralih ke Mi Fitness.", Toast.LENGTH_LONG).show()
            return
        }
        if (wasPaused) connectionMode.pause()
        syncCenterDialog?.dismiss()
        try {
            startActivity(launchIntent)
        } catch (e: android.content.ActivityNotFoundException) {
            if (!wasPaused) { connectionMode.resume(); if (wasConnected) resumeDirectSync() }
            Toast.makeText(this, "Mi Fitness tidak dapat dibuka.", Toast.LENGTH_LONG).show()
        } catch (e: SecurityException) {
            if (!wasPaused) { connectionMode.resume(); if (wasConnected) resumeDirectSync() }
            Toast.makeText(this, "Android tidak mengizinkan Mi Fitness dibuka.", Toast.LENGTH_LONG).show()
        }
    }

    private fun retryFromSyncCenter(metric: SyncMetric) {
        val failures = syncStatuses.entries.value.getValue(metric).failures
        val importOnly = metric == SyncMetric.SLEEP && failures.keys == setOf("#import")
        if (!importOnly) {
            val service = bleService
            val error = if (service == null) "Hubungkan jam terlebih dahulu." else service.retryMetric(metric)
            if (error != null) { Toast.makeText(this, error, Toast.LENGTH_LONG).show(); return }
            Toast.makeText(this, "Meminta ulang ${metric.title.lowercase()}…", Toast.LENGTH_SHORT).show()
        }
        if (metric == SyncMetric.SLEEP && Build.VERSION.SDK_INT >= 34) {
            if (ContextCompat.checkSelfPermission(this, HealthConnectSleepImporter.READ_SLEEP) == PackageManager.PERMISSION_GRANTED)
                importSleepHistory()
            else if (failures.containsKey("#import")) sleepPermissionLauncher.launch(HealthConnectSleepImporter.READ_SLEEP)
        }
    }

    private fun showSyncCenter() {
        if (syncCenterDialog != null) return
        val content = DialogSyncCenterBinding.inflate(layoutInflater)
        val dialog = BottomSheetDialog(this)
        syncCenterBinding = content
        syncCenterDialog = dialog
        SyncMetric.entries.forEach { metric ->
            val row = ItemSyncMetricBinding.inflate(layoutInflater, content.layoutSyncMetrics, false)
            row.metricTitle.text = metric.title
            row.btnRetryMetric.setOnClickListener { retryFromSyncCenter(metric) }
            content.layoutSyncMetrics.addView(row.root)
            syncRows[metric] = row
        }
        setupHandoff(content.handoff)
        content.btnCloseSyncCenter.setOnClickListener { dialog.dismiss() }
        dialog.setContentView(content.root)
        dialog.setOnDismissListener { syncCenterDialog = null; syncCenterBinding = null; syncRows.clear() }
        dialog.show()
        dialog.findViewById<FrameLayout>(com.google.android.material.R.id.design_bottom_sheet)?.let { sheet ->
            sheet.layoutParams.height = (resources.displayMetrics.heightPixels * 0.88).toInt()
            BottomSheetBehavior.from(sheet).apply { state = BottomSheetBehavior.STATE_EXPANDED; skipCollapsed = true }
        }
        renderSyncCenter()
    }

    @SuppressLint("SetTextI18n")
    private fun renderSyncCenter() {
        val paused = connectionMode.paused.value
        val ready = bleService?.watchfaceUploadReady?.value == true && !paused
        val entries = syncStatuses.entries.value
        val busy = entries.values.any { it.inProgress }
        val message = when {
            paused -> "DirectSync dijeda untuk Mi Fitness. Pilih Lanjutkan untuk terhubung kembali."
            ready && busy -> "Sedang mengambil data dari jam…"
            ready -> "Jam terhubung. Data yang tersedia tersimpan di HP."
            else -> "Jam belum terhubung. Hubungkan jam untuk mencoba pengambilan ulang."
        }
        fun handoff(item: ItemMiFitnessHandoffBinding) {
            item.tvConnectionMode.text = if (paused) message else "Jeda koneksi DirectSync sebelum menggunakan Mi Fitness agar koneksi jam tidak berebut."
            item.btnPauseMiFitness.text = if (paused) "Buka Mi Fitness" else "Jeda DirectSync & buka Mi Fitness"
            item.btnResumeDirectSync.visibility = if (paused) View.VISIBLE else View.GONE
        }
        handoff(binding.miFitnessHandoff)
        syncCenterBinding?.let { content ->
            content.tvSyncCenterConnection.text = message
            handoff(content.handoff)
        }
        val time = java.text.SimpleDateFormat("dd MMM yyyy HH:mm", Locale("id", "ID"))
        fun date(value: Long?) = value?.let { time.format(java.util.Date(it)) } ?: "Belum tercatat"
        syncRows.forEach { (metric, row) ->
            val entry = entries.getValue(metric)
            val evidence = viewModel.syncMetricEvidence.value[metric] ?: SyncMetricEvidence()
            val label = metricSyncLabel(entry.copy(inProgress = entry.inProgress || (metric == SyncMetric.SLEEP && sleepImportRunning)), evidence.complete)
            val importOnly = metric == SyncMetric.SLEEP && entry.failures.keys == setOf("#import")
            val color = ContextCompat.getColor(this, when (label) {
                MetricSyncLabel.COMPLETE -> R.color.primary_mint
                MetricSyncLabel.FAILED -> R.color.heart_rate_coral
                MetricSyncLabel.SYNCING -> R.color.secondary_cyan
                MetricSyncLabel.UNAVAILABLE -> R.color.on_surface_variant
            })
            row.metricStatus.text = when (label) {
                MetricSyncLabel.COMPLETE -> "Lengkap"
                MetricSyncLabel.UNAVAILABLE -> "Belum tersedia"
                MetricSyncLabel.FAILED -> "Gagal diunduh"
                MetricSyncLabel.SYNCING -> "Menyinkronkan"
            }
            row.metricStatus.setTextColor(color)
            row.metricStatus.backgroundTintList = ColorStateList.valueOf(androidx.core.graphics.ColorUtils.setAlphaComponent(color, 28))
            row.metricValue.text = evidence.value
            row.metricDataTime.text = "Data terakhir · ${date(evidence.dataAt)}"
            row.metricSyncTime.text = "Diterima · ${date(entry.receivedAt)}\nDiperiksa · ${date(entry.checkedAt)}"
            row.metricDetail.text = if (label == MetricSyncLabel.SYNCING)
                if (metric == SyncMetric.SLEEP && sleepImportRunning) "Membaca sesi Mi Fitness…" else "Sedang memeriksa ulang data…"
                else entry.failures.values.distinct().take(2).joinToString("\n").ifEmpty { evidence.detail }
            row.metricDetail.visibility = if (row.metricDetail.text.isEmpty()) View.GONE else View.VISIBLE
            row.metricDetail.setTextColor(if (label == MetricSyncLabel.FAILED) color else ContextCompat.getColor(this, R.color.on_surface_variant))
            row.btnRetryMetric.visibility = if (label == MetricSyncLabel.FAILED || label == MetricSyncLabel.UNAVAILABLE) View.VISIBLE else View.GONE
            row.btnRetryMetric.text = if (importOnly) "Coba impor Mi Fitness" else "Coba lagi"
            row.btnRetryMetric.isEnabled = (ready || importOnly) && !busy && !sleepImportRunning
            row.btnRetryMetric.alpha = if (row.btnRetryMetric.isEnabled) 1f else 0.5f
            row.btnRetryMetric.contentDescription = "Coba lagi ${metric.title}"
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
                    putExtra(BleSyncService.EXTRA_EXPLICIT_RESUME, true)
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
        if (Build.VERSION.SDK_INT >= 34 && ContextCompat.checkSelfPermission(this,
                HealthConnectSleepImporter.READ_SLEEP) == PackageManager.PERMISSION_GRANTED) importSleepHistory()
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
