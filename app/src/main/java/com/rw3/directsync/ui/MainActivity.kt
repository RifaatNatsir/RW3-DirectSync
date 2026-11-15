package com.rw3.directsync.ui

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.rw3.directsync.R
import com.rw3.directsync.ble.BleSyncService
import com.rw3.directsync.data.MiFitnessHealthConnectImporter
import com.rw3.directsync.data.SportTypeCatalog
import com.rw3.directsync.data.db.Rw3Database
import com.rw3.directsync.data.db.entity.ActivityRecordEntity
import com.rw3.directsync.data.db.entity.SleepSessionEntity
import com.rw3.directsync.databinding.ActivityMainBinding
import com.rw3.directsync.databinding.DialogAppWhitelistBinding
import com.rw3.directsync.databinding.DialogCredentialsSettingsBinding
import com.rw3.directsync.databinding.DialogEditWebhookBinding
import com.rw3.directsync.databinding.DialogSleepHistoryBinding
import com.rw3.directsync.databinding.DialogSyncCenterBinding
import com.rw3.directsync.databinding.ItemMiFitnessHandoffBinding
import com.rw3.directsync.databinding.ItemSleepHistoryBinding
import com.rw3.directsync.databinding.ItemSyncMetricBinding
import com.rw3.directsync.databinding.ItemWhitelistAppBinding
import com.rw3.directsync.databinding.ItemWorkoutHistoryBinding
import com.rw3.directsync.notification.Rw3NotificationListenerService
import com.rw3.directsync.sync.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * RW3-DirectSync Dashboard Activity:
 * - FigCraft dark Bento UI & shared Design System tokens
 * - 4 independent scrollable tabs (Dashboard, Activity, Terminal, Settings) with 240ms crossfade
 * - Detail screens (HeartRateDetailActivity, SleepInsightsActivity, WorkoutDetailActivity, WatchfaceActivity)
 */
private typealias SportFilter = SportTypeCatalog.Filter

class MainActivity : AppCompatActivity() {

    private enum class NavTab {
        DASHBOARD,
        ACTIVITY,
        TERMINAL,
        SETTINGS
    }

    private var currentTab = NavTab.DASHBOARD
    private var selectedSportFilter = SportFilter.ALL
    private var allWorkoutsCache: List<ActivityRecordEntity> = emptyList()
    private var featuredWorkoutId: String? = null
    private var featuredCurveJob: Job? = null

    private val exportTerminalLogsLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain")
    ) { uri: android.net.Uri? ->
        if (uri != null) exportTerminalLogsToUri(uri)
    }

    private val viewModel: MainViewModel by viewModels()
    private lateinit var binding: ActivityMainBinding

    private var bleService: BleSyncService? = null
    private var serviceStateJob: Job? = null
    private var blePickerScanJob: Job? = null
    private var bleScanInProgress = false
    private var lastWebhookDispatchAtMs = 0L
    private var pendingActionAfterPermission: (() -> Unit)? = null
    private val syncStatuses by lazy { SyncStatusRepository.get(this) }
    private val connectionMode by lazy { DirectSyncMode.get(this) }
    private var syncCenterDialog: BottomSheetDialog? = null
    private var syncCenterBinding: DialogSyncCenterBinding? = null
    private val syncRows = mutableMapOf<SyncMetric, ItemSyncMetricBinding>()
    private var sleepImportRunning = false
    private var askedHealthPermissionsOnce = false
    private var sleepHistoryRecords = emptyList<SleepSessionEntity>()
    private val healthPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        val anyGranted = grants.values.any { it } ||
            (Build.VERSION.SDK_INT >= 34 && MiFitnessHealthConnectImporter.hasAnyReadPermission(this))
        if (anyGranted) {
            importMiFitnessHistory()
        } else {
            binding.tvSleepImportStatus.visibility = View.VISIBLE
            binding.tvSleepImportStatus.text =
                "Izin Health Connect belum aktif. Ketuk 'Atur izin Health Connect' di tab Pengaturan untuk mengaktifkan akses."
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
                    launch { service.phoneFinderRinging.collect { renderDeviceFinder() } }
                    launch { service.findingWatch.collect { renderDeviceFinder() } }
                    launch { service.finderStatus.collect { renderDeviceFinder() } }
                    launch { service.watchfaceUploadReady.collect { renderDeviceFinder() } }
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
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val restoredTab = savedInstanceState?.getString(KEY_CURRENT_TAB)
            ?.let { name -> runCatching { NavTab.valueOf(name) }.getOrNull() }
            ?: NavTab.DASHBOARD
        savedInstanceState?.getString(KEY_SPORT_FILTER)
            ?.let { name -> runCatching { SportFilter.valueOf(name) }.getOrNull() }
            ?.let { selectedSportFilter = it }

        setupSystemBarInsets()
        setupBackNavigation()
        setupListeners()
        observeViewModel()
        updateNotificationUiState()
        selectTab(restoredTab, animate = false)
        openFinderFromNotification(intent)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(KEY_CURRENT_TAB, currentTab.name)
        outState.putString(KEY_SPORT_FILTER, selectedSportFilter.name)
    }

    private companion object {
        const val KEY_CURRENT_TAB = "state_current_tab"
        const val KEY_SPORT_FILTER = "state_sport_filter"
    }

    private fun setupSystemBarInsets() {
        val headerBaseTop = binding.mainHeaderBar.paddingTop
        val navBaseBottom = binding.bottomNavLayout.paddingBottom
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val sysBars = insets.getInsets(
                androidx.core.view.WindowInsetsCompat.Type.systemBars() or
                    androidx.core.view.WindowInsetsCompat.Type.displayCutout()
            )
            binding.mainHeaderBar.setPadding(
                binding.mainHeaderBar.paddingLeft,
                headerBaseTop + sysBars.top,
                binding.mainHeaderBar.paddingRight,
                binding.mainHeaderBar.paddingBottom
            )
            binding.bottomNavLayout.setPadding(
                binding.bottomNavLayout.paddingLeft,
                binding.bottomNavLayout.paddingTop,
                binding.bottomNavLayout.paddingRight,
                navBaseBottom + sysBars.bottom
            )
            insets
        }
        androidx.core.view.ViewCompat.requestApplyInsets(binding.root)
    }

    private fun setupBackNavigation() {
        onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (currentTab != NavTab.DASHBOARD) {
                    selectTab(NavTab.DASHBOARD)
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        openFinderFromNotification(intent)
    }

    private fun openFinderFromNotification(intent: Intent) {
        if (!intent.getBooleanExtra("open_device_finder", false)) return
        intent.removeExtra("open_device_finder")
        selectTab(NavTab.SETTINGS)
        binding.tabSettingsScroll.post {
            binding.tabSettingsScroll.smoothScrollTo(0, binding.deviceFinder.root.top)
        }
    }

    private fun renderDeviceFinder() {
        val svc = bleService
        val paused = connectionMode.paused.value
        val ready = svc?.watchfaceUploadReady?.value == true && !paused
        val finding = svc?.findingWatch?.value == true
        val ringing = svc?.phoneFinderRinging?.value == true
        val item = binding.deviceFinder
        item.btnFindWatch.isEnabled = ready || finding
        item.btnFindWatch.alpha = if (item.btnFindWatch.isEnabled) 1f else 0.55f
        item.btnFindWatch.text = if (finding) "Hentikan pencarian jam" else "Kirim sinyal ke jam"
        item.btnFindWatch.setIconResource(if (finding) R.drawable.ic_power else R.drawable.ic_watch)
        item.tvFinderStatus.text = when {
            paused -> "DirectSync sedang dijeda untuk Mi Fitness. Pilih Lanjutkan terlebih dahulu."
            !ready -> "Hubungkan DirectSync ke jam untuk mencari perangkat."
            finding -> svc?.finderStatus?.value ?: "Mengirim sinyal ke jam…"
            else -> "Siap mencari jam. Pencarian berhenti otomatis setelah 30 detik."
        }
        item.btnStopPhoneFinder.visibility = if (ringing) View.VISIBLE else View.GONE
        item.tvPhoneFinderStatus.text = if (ringing) "Smartwatch sedang mencari HP ini. Ketuk Hentikan untuk mematikan bunyi dan getaran."
            else "Untuk mencari HP, buka menu Cari HP di smartwatch. HP berdering dan bergetar hingga 60 detik."
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
            binding.tvNotificationAccessBadge.text = "Izin aktif"
            binding.tvNotificationAccessBadge.setBackgroundResource(R.drawable.bg_pill_mint)
            binding.tvNotificationAccessBadge.setTextColor(ContextCompat.getColor(this, R.color.primary_mint))
            binding.btnNotificationPermission.text = "Atur izin"
            binding.tvNotificationDescription.text = "Pesan aplikasi dan panggilan masuk dapat diteruskan ke jam. Pilih aplikasi yang diizinkan di bawah."
        } else {
            binding.tvNotificationAccessBadge.text = "Izin belum aktif"
            binding.tvNotificationAccessBadge.setBackgroundResource(R.drawable.bg_pill_coral)
            binding.tvNotificationAccessBadge.setTextColor(ContextCompat.getColor(this, R.color.heart_rate_coral))
            binding.btnNotificationPermission.text = "Aktifkan izin"
            binding.tvNotificationDescription.text = "Aktifkan akses notifikasi Android agar jam dapat menerima pesan dan panggilan dari HP."
        }

        val isWhitelistMode = Rw3NotificationListenerService.isWhitelistModeEnabled(this)
        val whitelistedCount = Rw3NotificationListenerService.getWhitelistedPackages(this).size
        if (isWhitelistMode) {
            binding.tvWhitelistSummary.text = "$whitelistedCount aplikasi diizinkan"
            binding.tvWhitelistSummary.setTextColor(ContextCompat.getColor(this, R.color.primary_mint))
        } else {
            binding.tvWhitelistSummary.text = "Semua aplikasi diizinkan"
            binding.tvWhitelistSummary.setTextColor(ContextCompat.getColor(this, R.color.on_surface_variant))
        }
    }

    override fun onDestroy() {
        blePickerScanJob?.cancel()
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
        // Header actions
        binding.btnHeaderProfile.setOnClickListener { selectTab(NavTab.SETTINGS) }
        binding.btnQuickWatchface.setOnClickListener {
            UiTransitions.startDetailActivity(this, Intent(this, WatchfaceActivity::class.java))
        }
        binding.btnHeaderActivitySync.setOnClickListener {
            if (bleService?.watchfaceUploadReady?.value == true && !connectionMode.paused.value) {
                triggerSyncAll()
            } else {
                ensurePermissionsAndRun { validateAndStartBleService() }
            }
        }

        // Dashboard Hero & Bento cards
        binding.btnDashboardConnect.setOnClickListener {
            ensurePermissionsAndRun { validateAndStartBleService() }
        }
        binding.btnSyncAll.setOnClickListener { triggerSyncAll() }
        binding.cardHeartRateStream.setOnClickListener {
            UiTransitions.startDetailActivity(this, Intent(this, HeartRateDetailActivity::class.java))
        }
        binding.btnToggleHeartRate.setOnClickListener { triggerStartHeartRate() }
        binding.cardSleepArchitecture.setOnClickListener {
            UiTransitions.startDetailActivity(this, Intent(this, SleepInsightsActivity::class.java))
        }
        binding.btnSleepInsights.setOnClickListener {
            UiTransitions.startDetailActivity(this, Intent(this, SleepInsightsActivity::class.java))
        }
        binding.btnViewSleepHistory.setOnClickListener { showAllSleepHistory() }
        binding.btnImportSleepHistory.setOnClickListener {
            triggerMiFitnessImport(interactive = true)
        }
        binding.cardRecentWorkoutDash.setOnClickListener {
            val latest = allWorkoutsCache.firstOrNull()
            if (latest != null) {
                openWorkoutDetail(latest.id)
            } else {
                selectTab(NavTab.ACTIVITY)
            }
        }

        // Activity Tab · Sport Filter Icon Tiles & Featured Session
        binding.tileSportAll.setOnClickListener { setSportFilter(SportFilter.ALL) }
        binding.tileSportRun.setOnClickListener { setSportFilter(SportFilter.RUN) }
        binding.tileSportWalk.setOnClickListener { setSportFilter(SportFilter.WALK) }
        binding.tileSportBike.setOnClickListener { setSportFilter(SportFilter.BIKE) }
        binding.tileSportGym.setOnClickListener { setSportFilter(SportFilter.GYM) }
        binding.cardFeaturedWorkout.setOnClickListener {
            featuredWorkoutId?.let { openWorkoutDetail(it) }
        }
        updateSportFilterUi()

        // Terminal & Sync Tab
        binding.btnTerminalSyncDetail.setOnClickListener { showSyncCenter() }
        binding.btnTerminalSyncAll.setOnClickListener {
            if (bleService?.watchfaceUploadReady?.value == true && !connectionMode.paused.value) {
                triggerSyncAll()
            } else {
                ensurePermissionsAndRun { validateAndStartBleService() }
            }
        }
        listOf(
            binding.rowSyncHr to SyncMetric.HEART_RATE,
            binding.rowSyncActivity to SyncMetric.STEPS,
            binding.rowSyncSleep to SyncMetric.SLEEP,
            binding.rowSyncWorkout to SyncMetric.WORKOUTS
        ).forEach { (row, metric) ->
            row.setOnClickListener {
                val entry = syncStatuses.entries.value.getValue(metric)
                if (entry.failures.isNotEmpty() || bleService?.watchfaceUploadReady?.value == true) {
                    showSyncCenter()
                } else {
                    showSyncCenter()
                }
            }
        }
        binding.btnTerminalHandoffBanner.setOnClickListener {
            selectTab(NavTab.SETTINGS)
            binding.tabSettingsScroll.post {
                binding.tabSettingsScroll.smoothScrollTo(0, binding.miFitnessHandoff.root.top)
            }
        }
        binding.btnCopyTerminalLogs.setOnClickListener { copyLogsToClipboard() }
        binding.btnClearTerminalLogs.setOnClickListener {
            bleService?.clearDiagnosticLogs()
            viewModel.clearDiagnosticLogs()
            binding.tvTerminalLogOutput.text = "Logs cleared. Menunggu event baru..."
            binding.tvTerminalLogCount.text = "0 baris"
        }
        binding.btnCmdPingSpp.setOnClickListener {
            val svc = bleService
            if (svc != null && svc.watchfaceUploadReady.value && !connectionMode.paused.value) {
                triggerSyncAll()
            } else {
                Toast.makeText(this, "Hubungkan jam untuk mengirim perintah ping/sinkronisasi.", Toast.LENGTH_SHORT).show()
            }
        }
        binding.btnCmdBatteryInfo.setOnClickListener {
            val bat = viewModel.batteryLevel.value
            val msg = if (bat != null) "Baterai jam terakhir terbaca: $bat%" else "Level baterai belum diterima dari jam."
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
        }
        binding.btnCmdExportLogs.setOnClickListener {
            val stamp = SimpleDateFormat("yyyyMMdd_HHmm", Locale.US).format(Date())
            exportTerminalLogsLauncher.launch("rw3_ble_logs_$stamp.txt")
        }

        // Settings & Atur Jam Tab
        setupHandoff(binding.miFitnessHandoff)
        binding.deviceFinder.btnFindWatch.setOnClickListener {
            val svc = bleService
            val error = svc?.findWatch(svc.findingWatch.value.not()) ?: if (svc == null) "Hubungkan jam terlebih dahulu." else null
            if (error != null) Toast.makeText(this, error, Toast.LENGTH_LONG).show()
        }
        binding.deviceFinder.btnStopPhoneFinder.setOnClickListener { bleService?.stopPhoneFinder() }
        binding.btnCopyMac.setOnClickListener {
            val mac = viewModel.macAddress.value.trim()
            if (mac.isEmpty()) {
                Toast.makeText(this, "MAC Address belum diatur.", Toast.LENGTH_SHORT).show()
            } else {
                copyTextToClipboard("RW3-MAC", mac)
                Toast.makeText(this, "MAC Address disalin ke clipboard", Toast.LENGTH_SHORT).show()
            }
        }
        binding.tvMacAddressDisplay.setOnClickListener { showCredentialsSettingsDialog() }
        binding.btnEditConnection.setOnClickListener { showCredentialsSettingsDialog() }
        binding.btnConnectMain.setOnClickListener {
            ensurePermissionsAndRun { validateAndStartBleService() }
        }
        binding.btnScanBlePicker.setOnClickListener {
            ensurePermissionsAndRun { startScanAndShowPicker() }
        }
        binding.btnDisconnectMain.setOnClickListener { stopBleService() }

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

        binding.cardWatchfaceWallpaper.setOnClickListener {
            UiTransitions.startDetailActivity(this, Intent(this, WatchfaceActivity::class.java))
        }
        binding.btnOpenWatchfaceManager.setOnClickListener {
            UiTransitions.startDetailActivity(this, Intent(this, WatchfaceActivity::class.java))
        }
        binding.rowSettingsMenuNotif.setOnClickListener {
            binding.tabSettingsScroll.smoothScrollTo(0, binding.cardSmartNotifications.top)
        }
        binding.rowSettingsMenuFinder.setOnClickListener {
            binding.tabSettingsScroll.smoothScrollTo(0, binding.deviceFinder.root.top)
        }
        binding.rowSettingsMenuWebhook.setOnClickListener {
            binding.tabSettingsScroll.smoothScrollTo(0, binding.cardTelemetryPipeline.top)
        }

        binding.btnNotificationPermission.setOnClickListener {
            Rw3NotificationListenerService.openNotificationAccessSettings(this)
        }
        binding.btnTestNotification.setOnClickListener {
            when {
                connectionMode.paused.value -> Toast.makeText(
                    this,
                    "DirectSync sedang dijeda untuk Mi Fitness. Pilih Lanjutkan terlebih dahulu.",
                    Toast.LENGTH_LONG
                ).show()
                bleService?.syncState?.value is BleSyncService.SyncState.Authenticated ||
                    bleService?.watchfaceUploadReady?.value == true -> {
                    val intent = Intent(this, BleSyncService::class.java).apply {
                        action = BleSyncService.ACTION_SEND_TEST_NOTIFICATION
                    }
                    startService(intent)
                    Toast.makeText(this, "Notifikasi tes dikirim ke Redmi Watch 3!", Toast.LENGTH_SHORT).show()
                }
                else -> Toast.makeText(
                    this,
                    "Hubungkan jam terlebih dahulu via tombol 'Hubungkan'.",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
        binding.btnFilterApps.setOnClickListener { showAppWhitelistDialog() }

        binding.boxWebhookEndpoint.setOnClickListener { showEditWebhookDialog() }
        binding.btnPushWebhookMain.setOnClickListener { triggerWebhookExport() }

        // Bottom Navigation Tabs (Minimalist icon dock)
        binding.navTabDash.setOnClickListener { selectTab(NavTab.DASHBOARD) }
        binding.navTabActivity.setOnClickListener { selectTab(NavTab.ACTIVITY) }
        binding.navTabTerminal.setOnClickListener { selectTab(NavTab.TERMINAL) }
        binding.navTabSettings.setOnClickListener { selectTab(NavTab.SETTINGS) }
    }

    private fun selectTab(tab: NavTab, animate: Boolean = true) {
        currentTab = tab
        val mint = ContextCompat.getColor(this, R.color.primary_mint)
        val muted = ContextCompat.getColor(this, R.color.on_surface_variant)

        val allTabs = listOf(
            binding.tabDashboardScroll,
            binding.tabActivityScroll,
            binding.layoutTerminalContainer,
            binding.tabSettingsScroll
        )
        val targetView = when (tab) {
            NavTab.DASHBOARD -> binding.tabDashboardScroll
            NavTab.ACTIVITY -> binding.tabActivityScroll
            NavTab.TERMINAL -> binding.layoutTerminalContainer
            NavTab.SETTINGS -> binding.tabSettingsScroll
        }

        if (animate && targetView.visibility != View.VISIBLE) {
            UiTransitions.crossfadeTo(targetView, allTabs)
        } else {
            allTabs.forEach { view ->
                view.animate().cancel()
                view.alpha = 1f
                view.visibility = if (view === targetView) View.VISIBLE else View.GONE
            }
        }

        when (tab) {
            NavTab.DASHBOARD -> {
                binding.tvHeaderAppTitle.text = "RW3 DirectSync"
                binding.headerDashActions.visibility = View.VISIBLE
                binding.btnHeaderActivitySync.visibility = View.GONE
                binding.badgeHeaderLiveRx.visibility = View.GONE
                binding.badgeHeaderFw.visibility = View.GONE
            }
            NavTab.ACTIVITY -> {
                binding.tvHeaderAppTitle.text = "Aktivitas Olahraga"
                binding.headerDashActions.visibility = View.GONE
                binding.btnHeaderActivitySync.visibility = View.VISIBLE
                binding.badgeHeaderLiveRx.visibility = View.GONE
                binding.badgeHeaderFw.visibility = View.GONE
            }
            NavTab.TERMINAL -> {
                binding.tvHeaderAppTitle.text = "Terminal"
                binding.headerDashActions.visibility = View.GONE
                binding.btnHeaderActivitySync.visibility = View.GONE
                binding.badgeHeaderLiveRx.visibility = View.VISIBLE
                binding.badgeHeaderFw.visibility = View.VISIBLE
                renderSyncCenter()
                if (binding.switchAutoScroll.isChecked) {
                    binding.terminalScrollView.post {
                        binding.terminalScrollView.scrollTo(0, binding.tvTerminalLogOutput.bottom)
                    }
                }
            }
            NavTab.SETTINGS -> {
                binding.tvHeaderAppTitle.text = "Atur Jam"
                binding.headerDashActions.visibility = View.VISIBLE
                binding.btnHeaderActivitySync.visibility = View.GONE
                binding.badgeHeaderLiveRx.visibility = View.GONE
                binding.badgeHeaderFw.visibility = View.GONE
                renderDeviceFinder()
                updateGroupedMenuSubtitles()
            }
        }

        fun styleNav(selected: Boolean, pill: View, icon: android.widget.ImageView, ind: View) {
            pill.setBackgroundResource(if (selected) R.drawable.bg_nav_active_pill else 0)
            icon.imageTintList = ColorStateList.valueOf(if (selected) mint else muted)
            ind.visibility = if (selected) View.VISIBLE else View.INVISIBLE
        }

        styleNav(tab == NavTab.DASHBOARD, binding.pillNavDash, binding.imgNavDash, binding.indNavDash)
        styleNav(tab == NavTab.ACTIVITY, binding.pillNavActivity, binding.imgNavActivity, binding.indNavActivity)
        styleNav(tab == NavTab.TERMINAL, binding.pillNavTerminal, binding.imgNavTerminal, binding.indNavTerminal)
        styleNav(tab == NavTab.SETTINGS, binding.pillNavSettings, binding.imgNavSettings, binding.indNavSettings)
    }

    @SuppressLint("SetTextI18n")
    private fun observeViewModel() {
        var latestSpo2Pct: Int? = null
        var latestStressScore: Int? = null
        fun updateSpo2StressLabel() {
            val spo2Text = latestSpo2Pct?.let { "$it%" } ?: "—"
            val stressText = latestStressScore?.let { "$it" } ?: "—"
            binding.tvSpo2StressCombined.text = "$spo2Text · $stressText"
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { syncStatuses.entries.collect { renderSyncCenter() } }
                launch { viewModel.syncMetricEvidence.collect { renderSyncCenter() } }
                launch { connectionMode.paused.collect { updateUiForSyncState(viewModel.syncState.value) } }

                // 1. MAC Address
                launch {
                    viewModel.macAddress.collect { mac ->
                        binding.tvMacAddressDisplay.text = mac.ifBlank { "Belum diatur" }
                        binding.btnCopyMac.isEnabled = mac.isNotBlank()
                        binding.btnCopyMac.alpha = if (mac.isNotBlank()) 1f else 0.4f
                    }
                }

                // 2. Webhook URL
                launch {
                    viewModel.webhookUrl.collect { url ->
                        val hasUrl = url.isNotBlank()
                        binding.tvWebhookUrlPreview.text = if (hasUrl) url else "Belum diatur · ketuk untuk menambah URL"
                        binding.tvWebhookUrlPreview.setTextColor(
                            ContextCompat.getColor(
                                this@MainActivity,
                                if (hasUrl) R.color.secondary_cyan else R.color.on_surface_muted
                            )
                        )
                    }
                }

                // 3. Battery Level
                launch {
                    viewModel.batteryLevel.collect { battery ->
                        val batteryStr = if (battery != null && battery > 0) "$battery%" else "--%"
                        binding.tvHeaderBattery.text = batteryStr
                        binding.tvDeviceBattery.text = batteryStr
                    }
                }

                // 4. Dashboard Hero Heart Rate & Compact Sparkline
                launch {
                    viewModel.latestHeartRate.collect { record ->
                        binding.tvLiveBpmValue.text = record?.bpm?.toString() ?: "—"
                        if (record != null) {
                            binding.tvHeroSyncTimeBadge.text = metricTime(record.timestamp)
                        }
                    }
                }
                launch {
                    viewModel.heartRate24hRecords.collect { records ->
                        binding.miniHrSparkline.setAccentColor(
                            ContextCompat.getColor(this@MainActivity, R.color.heart_rate_coral)
                        )
                        binding.miniHrSparkline.setRecords(records)
                    }
                }
                launch {
                    viewModel.heartRate24hStats.collect { stats ->
                        val avgBpm = stats.avgBpm
                        binding.tvHr24hStats.text = if (stats.count > 0 && avgBpm != null) {
                            "Min ${stats.minBpm ?: "—"} · $avgBpm · Maks ${stats.maxBpm ?: "—"}"
                        } else {
                            "Belum ada sampel"
                        }
                    }
                }
                launch {
                    viewModel.isHrStreaming.collect { streaming ->
                        binding.btnToggleHeartRate.text = if (streaming) "Hentikan" else "Ukur Sekarang"
                    }
                }

                // 5. Stored Records & Unsynced Queue
                launch {
                    viewModel.storedRecordsCount.collect { count ->
                        binding.tvStoredRecordsCount.text = numberFormat.format(count)
                        updateGroupedMenuSubtitles()
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

                // 7. Diagnostic Terminal Logs
                launch {
                    viewModel.diagnosticLogs.collect { logs ->
                        binding.tvTerminalLogCount.text = "${logs.size} baris"
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

                // 8. Daily Activity (Steps, Calories, Resting HR, Standing Hours, Progress Bars)
                launch {
                    viewModel.dailyActivity.collect { record ->
                        if (record != null) {
                            val steps = record.steps ?: 0
                            val cals = record.calories ?: 0
                            binding.tvDailyStepsPercent.text = record.steps?.let {
                                "Langkah · ${(it * 100 / 8000).coerceIn(0, 999)}%"
                            } ?: "Langkah · --%"
                            binding.tvDailyCaloriesPercent.text = record.calories?.let {
                                "Kalori · ${(it * 100 / 500).coerceIn(0, 999)}%"
                            } ?: "Kalori · --%"
                            binding.tvDailyStepsValue.text = record.steps?.let { numberFormat.format(it) } ?: "—"
                            binding.tvDailyCaloriesValue.text = record.calories?.let { numberFormat.format(it) } ?: "—"
                            binding.tvDailyActivityDate.text = SimpleDateFormat("dd MMM yyyy", Locale("id", "ID"))
                                .format(Date(record.timestamp))
                            binding.tvRestingHrValue.text = record.restingHeartRate?.let { "$it BPM" } ?: "—"
                            binding.tvStandHoursValue.text = record.standingHours?.let { "$it jam" } ?: "—"
                            updateProgressBar(binding.trackStepsProgress, binding.barStepsProgress, steps / 8000f)
                            updateProgressBar(binding.trackCaloriesProgress, binding.barCaloriesProgress, cals / 500f)
                        } else {
                            binding.tvDailyStepsPercent.text = "Langkah · --%"
                            binding.tvDailyCaloriesPercent.text = "Kalori · --%"
                            binding.tvDailyStepsValue.text = "—"
                            binding.tvDailyCaloriesValue.text = "—"
                            binding.tvDailyActivityDate.text = "Belum ada data"
                            binding.tvRestingHrValue.text = "—"
                            binding.tvStandHoursValue.text = "—"
                            updateProgressBar(binding.trackStepsProgress, binding.barStepsProgress, 0f)
                            updateProgressBar(binding.trackCaloriesProgress, binding.barCaloriesProgress, 0f)
                        }
                    }
                }

                // 9. SpO2 & Stress combined badge on Dashboard Activity card
                launch {
                    viewModel.latestSpo2.collect { record ->
                        latestSpo2Pct = record?.spo2
                        updateSpo2StressLabel()
                    }
                }
                launch {
                    viewModel.latestStress.collect { record ->
                        latestStressScore = record?.stress
                        updateSpo2StressLabel()
                    }
                }

                // 10. Workouts -> Dashboard Recent Card + Activity Tab (Filter, Weekly Summary, Featured, History)
                launch {
                    viewModel.workouts.collect { records ->
                        allWorkoutsCache = records
                        val latest = records.firstOrNull()
                        if (latest != null) {
                            binding.tvRecentWorkoutTitle.text = SportTypeCatalog.displayName(latest.sportType)
                            val dur = formatWorkoutDurationShort(latest)
                            val dist = formatWorkoutDistanceShort(latest)
                            val cal = latest.calories?.let { "${numberFormat.format(it)} kcal" } ?: "—"
                            val metricSeg = if (dist != null) "$dist · $dur" else dur
                            binding.tvRecentWorkoutSub.text = "$metricSeg · $cal · ${metricTime(latest.timestamp)}"
                        } else {
                            binding.tvRecentWorkoutTitle.text = "Belum ada sesi"
                            binding.tvRecentWorkoutSub.text = "Sinkronkan olahraga dari jam"
                        }
                        renderActivityTabWorkouts(records)
                    }
                }

                // 11. Sleep Bento Card Summary & History Cache
                launch {
                    viewModel.sleepSummary.collect { summary ->
                        updateSleepSummaryUi(summary)
                    }
                }
                launch {
                    viewModel.sleepHistory.collect { records ->
                        updateSleepHistoryUi(records)
                    }
                }
            }
        }
    }

    private fun updateProgressBar(track: View, bar: View, fraction: Float) {
        val clamped = fraction.coerceIn(0f, 1f)
        track.post {
            val totalWidth = track.width
            if (totalWidth <= 0) return@post
            val lp = bar.layoutParams
            lp.width = if (clamped <= 0f) {
                0
            } else {
                (totalWidth * clamped).toInt().coerceAtLeast((6 * resources.displayMetrics.density).toInt())
            }
            bar.layoutParams = lp
        }
    }

    private fun setSportFilter(filter: SportFilter) {
        selectedSportFilter = filter
        updateSportFilterUi()
        renderActivityTabWorkouts(allWorkoutsCache)
    }

    private fun updateSportFilterUi() {
        val mint = ContextCompat.getColor(this, R.color.primary_mint)
        val muted = ContextCompat.getColor(this, R.color.on_surface_variant)
        val ringDrawable = ContextCompat.getDrawable(this, R.drawable.bg_sport_tile_selected_ring)
        val entries = listOf(
            Triple(SportFilter.ALL, binding.tileSportAll, Pair(binding.iconBoxSportAll, binding.labelSportAll)),
            Triple(SportFilter.RUN, binding.tileSportRun, Pair(binding.iconBoxSportRun, binding.labelSportRun)),
            Triple(SportFilter.WALK, binding.tileSportWalk, Pair(binding.iconBoxSportWalk, binding.labelSportWalk)),
            Triple(SportFilter.BIKE, binding.tileSportBike, Pair(binding.iconBoxSportBike, binding.labelSportBike)),
            Triple(SportFilter.GYM, binding.tileSportGym, Pair(binding.iconBoxSportGym, binding.labelSportGym))
        )
        entries.forEach { (filter, tile, pair) ->
            val selected = filter == selectedSportFilter
            val (iconBox, label) = pair
            tile.alpha = if (selected) 1f else 0.78f
            iconBox.foreground = if (selected) ringDrawable else null
            label.setTextColor(if (selected) mint else muted)
        }
    }

    private fun matchesSportFilter(sportType: Int?, filter: SportFilter): Boolean =
        SportTypeCatalog.matchesFilter(sportType, filter)

    @SuppressLint("SetTextI18n")
    private fun renderActivityTabWorkouts(allRecords: List<ActivityRecordEntity>) {
        // Weekly KPI summary across the past 7 days
        val zone = java.time.ZoneId.systemDefault()
        val today = java.time.LocalDate.now(zone)
        val weekStart = today.minusDays(6)
        val fmtShort = java.time.format.DateTimeFormatter.ofPattern("dd MMM", Locale("id", "ID"))
        binding.tvActivityWeeklyDate.text = "${weekStart.format(fmtShort)} – ${today.format(fmtShort)}"

        val weekRecords = allRecords.filter { rec ->
            val date = java.time.Instant.ofEpochMilli(rec.timestamp).atZone(zone).toLocalDate()
            date in weekStart..today
        }
        val totalSec = weekRecords.sumOf { resolveDurationSeconds(it) }
        val totalCal = weekRecords.sumOf { it.calories ?: 0 }
        val totalMin = totalSec / 60
        binding.tvWeeklySessionsCount.text = weekRecords.size.toString()
        binding.tvWeeklyActiveDuration.text = if (totalMin >= 60) "${totalMin / 60}j ${totalMin % 60}m" else "${totalMin}m"
        binding.tvWeeklyCalories.text = numberFormat.format(totalCal)

        val filtered = allRecords.filter { matchesSportFilter(it.sportType, selectedSportFilter) }
        val featured = filtered.firstOrNull()
        if (featured != null) {
            featuredWorkoutId = featured.id
            binding.cardFeaturedWorkout.visibility = View.VISIBLE
            binding.tvFeaturedWorkoutTitle.text = SportTypeCatalog.displayName(featured.sportType)
            val dateClock = SimpleDateFormat("dd MMM · HH:mm", Locale("id", "ID")).format(Date(featured.timestamp))
            val featDist = formatWorkoutDistanceShort(featured)
            binding.tvFeaturedWorkoutSub.text = if (featDist != null) "$dateClock · $featDist" else dateClock
            binding.tvFeaturedWorkoutBadge.text = featured.averageHeartRate?.let { "♥ $it BPM" } ?: "Ketuk Detail"
            val durSec = resolveDurationSeconds(featured)
            binding.tvFeatDuration.text = if (durSec > 0) formatHmsShort(durSec) else "—"
            binding.tvFeatHrAvg.text = featured.averageHeartRate?.let { "$it BPM" } ?: "—"
            binding.tvFeatHrMax.text = featured.maxHeartRate?.let { "$it BPM" } ?: "—"
            binding.tvFeatCalories.text = featured.calories?.let { "${numberFormat.format(it)} kcal" } ?: "—"
            loadFeaturedWorkoutCurve(featured)
        } else {
            featuredWorkoutId = null
            featuredCurveJob?.cancel()
            binding.cardFeaturedWorkout.visibility = View.GONE
            binding.featuredWorkoutSparkline.setRecords(emptyList())
        }

        binding.layoutWorkoutHistoryList.removeAllViews()
        binding.tvWorkoutSummary.text = if (filtered.isEmpty()) {
            "Tidak ada sesi untuk filter ini"
        } else {
            "${filtered.size} sesi ditampilkan · ketuk untuk detail"
        }
        if (filtered.isNotEmpty()) {
            filtered.forEach { record -> addWorkoutRowView(record) }
        } else {
            binding.layoutWorkoutHistoryList.addView(TextView(this@MainActivity).apply {
                text = if (allRecords.isEmpty()) {
                    "Belum ada sesi olahraga. Sinkronkan riwayat dari jam."
                } else {
                    "Belum ada sesi pada kategori olahraga ini."
                }
                textSize = 13f
                setTextColor(ContextCompat.getColor(this@MainActivity, R.color.on_surface_variant))
            })
        }
    }

    @SuppressLint("SetTextI18n")
    private fun loadFeaturedWorkoutCurve(record: ActivityRecordEntity) {
        featuredCurveJob?.cancel()
        val endTs = record.endTimestamp ?: (record.timestamp + (record.activeSeconds ?: 0) * 1000L)
        if (endTs <= record.timestamp) {
            binding.featuredWorkoutSparkline.setRecords(emptyList())
            binding.tvFeaturedCurveNote.text = "Ketuk kartu ini untuk melihat detail sesi olahraga."
            return
        }
        binding.tvFeaturedCurveNote.text = "Memuat kurva detak jantung sesi…"
        featuredCurveJob = lifecycleScope.launch {
            val samples = withContext(Dispatchers.IO) {
                Rw3Database.getDatabase(applicationContext)
                    .heartRateDao()
                    .getRecordsBetween(record.timestamp, endTs)
            }
            binding.featuredWorkoutSparkline.setAccentColor(
                ContextCompat.getColor(this@MainActivity, R.color.heart_rate_coral)
            )
            binding.featuredWorkoutSparkline.setRecords(samples)
            binding.tvFeaturedCurveNote.text = if (samples.size >= 2) {
                "${samples.size} sampel HR terekam selama sesi · Ketuk untuk detail"
            } else {
                "Sampel HR detail tidak terekam saat sesi ini · Ketuk untuk rincian"
            }
        }
    }

    private fun openWorkoutDetail(workoutId: String) {
        UiTransitions.startDetailActivity(
            this,
            Intent(this, WorkoutDetailActivity::class.java)
                .putExtra(WorkoutDetailActivity.EXTRA_WORKOUT_ID, workoutId)
        )
    }

    private fun resolveDurationSeconds(record: ActivityRecordEntity): Int {
        val direct = record.activeSeconds ?: 0
        if (direct > 0) return direct
        val end = record.endTimestamp ?: return 0
        return ((end - record.timestamp) / 1000L).toInt().coerceAtLeast(0)
    }

    private fun formatHmsShort(seconds: Int): String {
        val h = seconds / 3600
        val m = (seconds % 3600) / 60
        val s = seconds % 60
        return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, s)
        else String.format(Locale.US, "%d:%02d", m, s)
    }

    private fun formatWorkoutDurationShort(record: ActivityRecordEntity): String {
        val sec = resolveDurationSeconds(record)
        return if (sec > 0) formatHmsShort(sec) else "Durasi —"
    }

    @SuppressLint("SetTextI18n")
    private fun addWorkoutRowView(record: ActivityRecordEntity) {
        val item = ItemWorkoutHistoryBinding.inflate(layoutInflater, binding.layoutWorkoutHistoryList, false)
        item.workoutTitle.text = SportTypeCatalog.displayName(record.sportType)
        item.workoutDate.text = SimpleDateFormat("dd MMM yyyy", Locale("id", "ID"))
            .format(Date(record.timestamp))
        val clock = SimpleDateFormat("HH:mm", Locale.getDefault())
        val start = clock.format(Date(record.timestamp))
        val dur = formatWorkoutDurationShort(record)
        val dist = formatWorkoutDistanceShort(record)
        val avgHr = record.averageHeartRate?.let { if (dist != null) " · ♥ $it" else " · ♥ $it BPM" } ?: ""
        item.workoutPeriod.text = if (dist != null) "$start · $dist · $dur$avgHr" else "$start · $dur$avgHr"
        item.workoutCalories.text = record.calories?.let { "${numberFormat.format(it)} kcal" } ?: "—"
        item.workoutIcon.setImageResource(SportTypeCatalog.iconRes(record.sportType))
        item.root.setOnClickListener { openWorkoutDetail(record.id) }
        binding.layoutWorkoutHistoryList.addView(item.root)
    }

    private fun updateGroupedMenuSubtitles() {
        val notifOk = Rw3NotificationListenerService.isNotificationAccessGranted(this)
        val wlCount = Rw3NotificationListenerService.getWhitelistedPackages(this).size
        val wlMode = Rw3NotificationListenerService.isWhitelistModeEnabled(this)
        binding.tvGroupedNotifSub.text = when {
            !notifOk -> "Izin akses notifikasi belum aktif"
            wlMode -> "Aktif · $wlCount aplikasi diizinkan"
            else -> "Aktif · Semua aplikasi diteruskan"
        }
        val stored = viewModel.storedRecordsCount.value
        val unsynced = viewModel.unsyncedRecordsCount.value
        binding.tvGroupedStorageSub.text = "${numberFormat.format(stored)} rekaman · ${numberFormat.format(unsynced)} antrean"
    }

    private fun exportTerminalLogsToUri(uri: android.net.Uri) {
        val snapshot = viewModel.diagnosticLogs.value.toList()
        if (snapshot.isEmpty()) {
            Toast.makeText(this, "Belum ada log terminal untuk diekspor.", Toast.LENGTH_SHORT).show()
            return
        }
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                contentResolver.openOutputStream(uri)?.use { out ->
                    out.write(snapshot.joinToString("\n").toByteArray(Charsets.UTF_8))
                }
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, "${snapshot.size} baris log diekspor.", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, "Gagal mengekspor log: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun formatWorkoutDistanceShort(record: ActivityRecordEntity): String? {
        val m = record.distanceMeters?.takeIf { it >= 10.0 } ?: return null
        val km = m / 1000.0
        val txt = if (km >= 10.0) String.format(Locale.US, "%.1f", km) else String.format(Locale.US, "%.2f", km).trimEnd('0').trimEnd('.')
        return "$txt km"
    }

    private fun triggerMiFitnessImport(interactive: Boolean) {
        if (!MiFitnessHealthConnectImporter.isAvailable(this)) {
            if (interactive) {
                binding.tvSleepImportStatus.visibility = View.VISIBLE
                binding.tvSleepImportStatus.text =
                    "Impor Mi Fitness membutuhkan Android 14 atau lebih baru. Sinkronisasi langsung dari jam tetap tersedia."
            }
            return
        }
        val supported = MiFitnessHealthConnectImporter.supportedPermissions(this)
        val missing = MiFitnessHealthConnectImporter.missingPermissions(this)
        if (interactive && missing.isNotEmpty() && !askedHealthPermissionsOnce) {
            askedHealthPermissionsOnce = true
            binding.tvSleepImportStatus.visibility = View.VISIBLE
            binding.tvSleepImportStatus.text = "Meminta izin akses data Mi Fitness melalui Health Connect…"
            healthPermissionLauncher.launch(supported.toTypedArray())
        } else if (MiFitnessHealthConnectImporter.hasAnyReadPermission(this)) {
            importMiFitnessHistory()
        } else if (interactive) {
            healthPermissionLauncher.launch(supported.toTypedArray())
        }
    }

    private fun openHealthConnectPermissions() {
        if (!MiFitnessHealthConnectImporter.isAvailable(this)) {
            Toast.makeText(this, "Health Connect bawaan sistem tersedia di Android 14+.", Toast.LENGTH_LONG).show()
            return
        }
        runCatching {
            startActivity(MiFitnessHealthConnectImporter.managePermissionsIntent(this))
        }.recoverCatching {
            startActivity(MiFitnessHealthConnectImporter.healthHomeSettingsIntent())
            Toast.makeText(
                this,
                "Pilih 'RW3-DirectSync' di Health Connect untuk mengatur seluruh izin termasuk Rute olahraga.",
                Toast.LENGTH_LONG
            ).show()
        }.onFailure {
            Toast.makeText(this, "Buka Pengaturan > Health Connect untuk mengatur izin aplikasi.", Toast.LENGTH_LONG).show()
        }
    }

    private fun importMiFitnessHistory() {
        if (Build.VERSION.SDK_INT < 34 || sleepImportRunning) return
        sleepImportRunning = true
        renderSyncCenter()
        binding.btnImportSleepHistory.isEnabled = false
        binding.tvSleepImportStatus.visibility = View.VISIBLE
        binding.tvSleepImportStatus.text = "Mengimpor seluruh riwayat Mi Fitness dari Health Connect…"
        lifecycleScope.launch {
            try {
                val summary = MiFitnessHealthConnectImporter(this@MainActivity).importAll()
                val parts = buildList {
                    if (summary.workouts > 0) add("${summary.workouts} olahraga")
                    if (summary.sleepSessions > 0) add("${summary.sleepSessions} tidur")
                    if (summary.dailyDays > 0) add("${summary.dailyDays} hari aktivitas")
                    if (summary.heartRateSamples > 0) add("${numberFormat.format(summary.heartRateSamples)} sampel HR")
                    if (summary.spo2Records > 0) add("${summary.spo2Records} SpO₂")
                }
                val missing = MiFitnessHealthConnectImporter.missingPermissions(this@MainActivity)
                binding.tvSleepImportStatus.text = if (parts.isNotEmpty()) {
                    val hint = when {
                        summary.routesPendingPermission > 0 ->
                            " (${summary.routesPendingPermission} sesi memiliki rute GPS — aktifkan 'Rute olahraga' via Atur izin Health Connect)"
                        missing.isNotEmpty() ->
                            " Ketuk 'Atur izin Health Connect' bila ada kategori yang belum diizinkan."
                        else -> ""
                    }
                    "Diimpor dari Mi Fitness: ${parts.joinToString(" · ")}.$hint"
                } else {
                    "Belum ada data Mi Fitness di Health Connect (${summary.grantedTypes} izin aktif). Sinkronkan Mi Fitness dahulu lalu coba kembali."
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                syncStatuses.importFailed(e.message ?: "Periksa izin Health Connect.")
                binding.tvSleepImportStatus.text = "Riwayat belum dapat dibaca: ${e.message ?: "periksa izin Health Connect"}"
            } finally {
                sleepImportRunning = false
                binding.btnImportSleepHistory.isEnabled = true
                renderSyncCenter()
            }
        }
    }

    private fun bindSleepRow(item: ItemSleepHistoryBinding, session: SleepSessionEntity) {
        item.sleepDate.text = SimpleDateFormat("EEE, dd MMM", Locale("id", "ID"))
            .format(Date(session.endTimestamp))
        val clock = SimpleDateFormat("HH:mm", Locale.getDefault())
        item.sleepTime.text = "${clock.format(Date(session.startTimestamp))} – ${clock.format(Date(session.endTimestamp))}"
        item.sleepDuration.text = formatSleepMinutes(session.totalMinutes)
        item.sleepPhaseBar.setPhases(
            session.deepMinutes.takeIf { session.hasStageData },
            session.lightMinutes.takeIf { session.hasStageData },
            session.remMinutes.takeIf { session.hasStageData }
        )
        item.sleepPhaseCaption.text = if (session.hasStageData)
            "Deep ${session.deepMinutes}m · Light ${session.lightMinutes}m · REM ${session.remMinutes}m"
        else "Rincian fase belum tersedia"
        item.root.setOnClickListener { showSleepDetails(session) }
    }

    @SuppressLint("SetTextI18n")
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
        val dialog = BottomSheetDialog(this)
        val pageSize = 7
        var page = 0
        val pages = (records.size + pageSize - 1) / pageSize
        var visible = records.take(pageSize)
        val adapter = object : RecyclerView.Adapter<SleepHistoryHolder>() {
            override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = SleepHistoryHolder(
                ItemSleepHistoryBinding.inflate(layoutInflater, parent, false)
            )
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
                BottomSheetBehavior.from(sheet).state = BottomSheetBehavior.STATE_EXPANDED
            }
        }
        renderPage()
        dialog.show()
    }

    private fun showSleepDetails(session: SleepSessionEntity) {
        UiTransitions.startDetailActivity(
            this,
            Intent(this, SleepInsightsActivity::class.java)
                .putExtra(SleepInsightsActivity.EXTRA_SESSION_START, session.startTimestamp)
        )
    }

    private fun formatSleepMinutes(minutes: Int): String =
        if (minutes >= 60) "${minutes / 60}j ${minutes % 60}m" else "${minutes}m"

    private fun metricTime(timestamp: Long): String =
        SimpleDateFormat("dd MMM HH:mm", Locale.getDefault()).format(Date(timestamp))

    @SuppressLint("SetTextI18n")
    private fun updateSleepSummaryUi(summary: MainViewModel.SleepSummary) {
        val clockFmt = SimpleDateFormat("HH:mm", Locale.getDefault())
        val dayFmt = SimpleDateFormat("dd MMM", Locale("id", "ID"))
        binding.tvTotalSleepValue.text = if (summary.hasData) formatSleepMinutes(summary.totalMinutes) else "—"
        binding.tvSleepPeriod.text = if (summary.hasData)
            "${clockFmt.format(Date(summary.startTimestamp))}–${clockFmt.format(Date(summary.endTimestamp))} · ${dayFmt.format(Date(summary.endTimestamp))}" else "Belum ada sesi"
        binding.tvSleepSource.text = if (summary.sourceFileId.startsWith("healthconnect:")) "Mi Fitness" else "Jam"
        binding.sleepPhaseBar.setPhases(summary.deepMinutes.takeIf { summary.hasData && summary.hasStageData },
            summary.lightMinutes.takeIf { summary.hasData && summary.hasStageData },
            summary.remMinutes.takeIf { summary.hasData && summary.hasStageData })
        binding.tvSleepLegendCompact.text = if (summary.hasStageData) {
            "Dlm ${formatSleepMinutes(summary.deepMinutes)} · Rgn ${formatSleepMinutes(summary.lightMinutes)} · REM ${formatSleepMinutes(summary.remMinutes)}"
        } else {
            "Fase belum tersedia"
        }
        binding.tvTotalSleepValue.contentDescription = "Total tidur ${summary.totalMinutes / 60} jam ${summary.totalMinutes % 60} menit"
    }

    @SuppressLint("SetTextI18n")
    private fun updateUiForSyncState(state: BleSyncService.SyncState) {
        val mintColor = ContextCompat.getColor(this, R.color.primary_mint)
        val cyanColor = ContextCompat.getColor(this, R.color.secondary_cyan)
        val coralColor = ContextCompat.getColor(this, R.color.heart_rate_coral)
        val mutedColor = ContextCompat.getColor(this, R.color.on_surface_variant)
        val paused = connectionMode.paused.value
        val connected = !paused && (state is BleSyncService.SyncState.Authenticated ||
            state is BleSyncService.SyncState.StreamingHeartRate || state is BleSyncService.SyncState.SyncingSleep ||
            state is BleSyncService.SyncState.SleepSyncCompleted || state is BleSyncService.SyncState.WebhookDispatched)

        binding.btnDashboardConnect.visibility = if (connected) View.GONE else View.VISIBLE
        binding.btnSyncAll.visibility = if (connected) View.VISIBLE else View.GONE
        binding.btnSyncAll.isEnabled = connected && state !is BleSyncService.SyncState.SyncingSleep
        binding.btnSyncAll.alpha = if (binding.btnSyncAll.isEnabled) 1f else 0.6f
        binding.btnSyncAll.text = if (state is BleSyncService.SyncState.SyncingSleep) "Mengambil…" else "Sinkronkan"
        binding.btnToggleHeartRate.isEnabled = connected
        binding.btnToggleHeartRate.alpha = if (connected) 1f else 0.55f
        binding.btnDashboardConnect.text = if (paused) "Lanjutkan DirectSync" else "Hubungkan jam"
        binding.btnDashboardConnect.isEnabled = !connected || paused

        when (state) {
            is BleSyncService.SyncState.Idle -> {
                binding.tvHeaderGattStatus.text = " Belum terhubung"
                binding.tvHeaderGattStatus.setTextColor(mutedColor)
                binding.imgHeaderGattDot.setImageResource(R.drawable.dot_pulse_coral)
                binding.tvDeviceStatusTag.text = "Belum terhubung"
                binding.tvDeviceStatusTag.setTextColor(mutedColor)
                binding.tvHeroSyncSubtitle.text = "Redmi Watch 3 siap dihubungkan via DirectSync"
                binding.badgeHeaderLiveRx.text = "IDLE"
                binding.badgeHeaderLiveRx.setTextColor(mutedColor)
            }

            is BleSyncService.SyncState.Connecting -> {
                binding.tvHeaderGattStatus.text = " Menghubungkan…"
                binding.tvHeaderGattStatus.setTextColor(cyanColor)
                binding.imgHeaderGattDot.setImageResource(R.drawable.dot_pulse_green)
                binding.tvDeviceStatusTag.text = "Menghubungkan…"
                binding.tvDeviceStatusTag.setTextColor(cyanColor)
                binding.tvHeroSyncSubtitle.text = "Mencari & membuka GATT Redmi Watch 3…"
                binding.badgeHeaderLiveRx.text = "CONN"
                binding.badgeHeaderLiveRx.setTextColor(cyanColor)
            }

            is BleSyncService.SyncState.DiscoveringServices -> {
                binding.tvHeaderGattStatus.text = " Menyiapkan koneksi…"
                binding.tvHeaderGattStatus.setTextColor(cyanColor)
                binding.tvDeviceStatusTag.text = "Menyiapkan koneksi…"
                binding.tvDeviceStatusTag.setTextColor(cyanColor)
                binding.tvHeroSyncSubtitle.text = "Menyiapkan layanan BLE & GATT…"
            }

            is BleSyncService.SyncState.Authenticating -> {
                binding.tvHeaderGattStatus.text = " Memverifikasi jam…"
                binding.tvHeaderGattStatus.setTextColor(cyanColor)
                binding.tvDeviceStatusTag.text = "Memverifikasi jam…"
                binding.tvDeviceStatusTag.setTextColor(cyanColor)
                binding.tvHeroSyncSubtitle.text = "Autentikasi sesi terenkripsi…"
            }

            is BleSyncService.SyncState.Authenticated -> {
                binding.tvHeaderGattStatus.text = " Terhubung"
                binding.tvHeaderGattStatus.setTextColor(mintColor)
                binding.imgHeaderGattDot.setImageResource(R.drawable.dot_pulse_green)
                binding.tvDeviceStatusTag.text = "Terhubung"
                binding.tvDeviceStatusTag.setTextColor(mintColor)
                binding.tvAutoReconnectStatus.text = "Siap menyambung kembali jika koneksi terputus."
                binding.tvHeroSyncSubtitle.text = "DirectSync aktif · Siap menyinkronkan data jam"
                binding.badgeHeaderLiveRx.text = "LIVE RX"
                binding.badgeHeaderLiveRx.setTextColor(mintColor)
            }

            is BleSyncService.SyncState.StreamingHeartRate -> {
                binding.tvHeaderGattStatus.text = " HR: ${state.latestBpm} BPM"
                binding.tvHeaderGattStatus.setTextColor(coralColor)
                binding.imgHeaderGattDot.setImageResource(R.drawable.dot_pulse_green)
                binding.tvDeviceStatusTag.text = "Terhubung"
                binding.tvDeviceStatusTag.setTextColor(mintColor)
                binding.tvHeroSyncSubtitle.text = "Eco HR aktif · ${state.latestBpm} BPM terbaca"
                binding.badgeHeaderLiveRx.text = "HR ${state.latestBpm}"
                binding.badgeHeaderLiveRx.setTextColor(coralColor)
            }

            is BleSyncService.SyncState.SyncingSleep -> {
                binding.tvHeaderGattStatus.text = " Mengambil riwayat…"
                binding.tvHeaderGattStatus.setTextColor(cyanColor)
                binding.tvDeviceStatusTag.text = "Mengambil riwayat…"
                binding.tvDeviceStatusTag.setTextColor(cyanColor)
                binding.tvHeroSyncSubtitle.text = "Menarik data aktivitas, tidur, HR & olahraga…"
                binding.badgeHeaderLiveRx.text = "SYNC"
                binding.badgeHeaderLiveRx.setTextColor(cyanColor)
            }

            is BleSyncService.SyncState.SleepSyncCompleted -> {
                binding.tvHeaderGattStatus.text = " Terhubung"
                binding.tvDeviceStatusTag.text = "Riwayat tersimpan"
                binding.tvDeviceStatusTag.setTextColor(mintColor)
                binding.tvHeroSyncSubtitle.text = "Riwayat tersimpan di HP · DirectSync aktif"
                binding.badgeHeaderLiveRx.text = "LIVE RX"
                binding.badgeHeaderLiveRx.setTextColor(mintColor)
                Toast.makeText(this, "Berhasil menyimpan ${state.stagesSaved} stage tidur ke SQLite!", Toast.LENGTH_SHORT).show()
            }

            is BleSyncService.SyncState.WebhookDispatched -> {
                binding.tvDeviceStatusTag.text = "Ekspor selesai"
                binding.tvDeviceStatusTag.setTextColor(mintColor)
                binding.tvWebhookLastAck.text = "● Push Berhasil (${state.hrCount} HR, ${state.sleepCount} Sleep, ${state.activityCount} aktivitas)"
                binding.tvWebhookLastAck.setTextColor(mintColor)
            }

            is BleSyncService.SyncState.Disconnected -> {
                binding.tvHeaderGattStatus.text = " Terputus"
                binding.tvHeaderGattStatus.setTextColor(coralColor)
                binding.imgHeaderGattDot.setImageResource(R.drawable.dot_pulse_coral)
                binding.tvDeviceStatusTag.text = "Terputus"
                binding.tvDeviceStatusTag.setTextColor(coralColor)
                binding.tvAutoReconnectStatus.text = if (state.willReconnect) "Mencoba menyambung kembali secara otomatis." else "Koneksi terputus. Hubungkan jam untuk mencoba lagi."
                binding.tvHeroSyncSubtitle.text = if (state.willReconnect) "Koneksi terputus · Menunggu reconnect otomatis" else "Koneksi terputus"
                binding.badgeHeaderLiveRx.text = "OFFLINE"
                binding.badgeHeaderLiveRx.setTextColor(coralColor)
            }

            is BleSyncService.SyncState.Error -> {
                binding.tvHeaderGattStatus.text = " Koneksi bermasalah"
                binding.tvHeaderGattStatus.setTextColor(coralColor)
                binding.imgHeaderGattDot.setImageResource(R.drawable.dot_pulse_coral)
                binding.tvDeviceStatusTag.text = "Koneksi bermasalah"
                binding.tvDeviceStatusTag.setTextColor(coralColor)
                binding.tvAutoReconnectStatus.text = "Koneksi bermasalah: ${state.message}"
                if (state.message.startsWith("Webhook error:")) {
                    binding.tvWebhookLastAck.text = "● ${state.message}"
                    binding.tvWebhookLastAck.setTextColor(coralColor)
                }
                binding.tvHeroSyncSubtitle.text = "Koneksi bermasalah · Ketuk untuk coba ulang"
                binding.badgeHeaderLiveRx.text = "ERR"
                binding.badgeHeaderLiveRx.setTextColor(coralColor)
            }
        }
        if (paused) {
            binding.tvHeaderGattStatus.text = " Dijeda · Mi Fitness"
            binding.tvDeviceStatusTag.text = "Jeda Mi Fitness"
            binding.btnDashboardConnect.text = "Lanjutkan DirectSync"
            binding.btnSyncAll.isEnabled = false
            binding.btnToggleHeartRate.isEnabled = false
            binding.tvAutoReconnectStatus.text = "DirectSync dijeda sampai Anda memilih Lanjutkan."
            binding.tvHeroSyncSubtitle.text = "Dijeda untuk Mi Fitness · Pilih Lanjutkan untuk menghubungkan lagi"
            binding.badgeHeaderLiveRx.text = "PAUSED"
            binding.badgeHeaderLiveRx.setTextColor(mutedColor)
        }
        val canDisconnect = paused ||
            (state !is BleSyncService.SyncState.Idle &&
                !(state is BleSyncService.SyncState.Disconnected && !state.willReconnect))
        binding.btnDisconnectMain.isEnabled = canDisconnect
        binding.btnDisconnectMain.alpha = if (canDisconnect) 1f else 0.45f
        val isConnecting = !paused && (state is BleSyncService.SyncState.Connecting ||
            state is BleSyncService.SyncState.DiscoveringServices ||
            state is BleSyncService.SyncState.Authenticating)
        binding.btnConnectMain.isEnabled = !isConnecting
        binding.btnConnectMain.alpha = if (isConnecting) 0.6f else 1f
        binding.btnConnectMain.text = when {
            paused -> "Lanjutkan"
            isConnecting -> "Menghubungkan…"
            else -> "Hubungkan"
        }
        renderSyncCenter()
        renderDeviceFinder()
    }

    @SuppressLint("SetTextI18n")
    private fun updateMediaUi(media: BleSyncService.MediaPlaybackInfo) {
        val hasTrack = media.title.isNotEmpty()
        binding.tvMediaTrackTitle.text = if (hasTrack) media.title else "Belum ada media"
        binding.tvMediaArtist.text = if (hasTrack && media.artist.isNotEmpty()) media.artist else "Putar musik di HP untuk mulai."
        binding.tvMediaVolumePercent.text = "Volume ${media.volumePercent}%"

        val playPauseBtn = binding.btnMediaPlayPause as? com.google.android.material.button.MaterialButton
        if (media.isPlaying) {
            binding.tvMediaStatusBadge.text = "Memutar"
            binding.tvMediaStatusBadge.setTextColor(ContextCompat.getColor(this, R.color.primary_mint))
            binding.btnMediaPlayPause.text = "Jeda"
            playPauseBtn?.setIconResource(R.drawable.ic_pause)
        } else {
            binding.tvMediaStatusBadge.text = if (hasTrack) "Jeda" else "Siap"
            binding.tvMediaStatusBadge.setTextColor(ContextCompat.getColor(this, R.color.secondary_cyan))
            binding.btnMediaPlayPause.text = "Putar"
            playPauseBtn?.setIconResource(R.drawable.ic_play_arrow)
        }
    }

    private fun setupHandoff(item: ItemMiFitnessHandoffBinding) {
        item.btnPauseMiFitness.setOnClickListener { openMiFitness() }
        item.btnResumeDirectSync.setOnClickListener { resumeDirectSync() }
        item.btnImportAllMiFitness.setOnClickListener {
            syncCenterDialog?.dismiss()
            triggerMiFitnessImport(interactive = true)
        }
        item.btnManageHealthPermissions.setOnClickListener {
            openHealthConnectPermissions()
        }
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
        val importOnly = failures.keys == setOf("#import")
        if (!importOnly) {
            val service = bleService
            val error = if (service == null) "Hubungkan jam terlebih dahulu." else service.retryMetric(metric)
            if (error != null) { Toast.makeText(this, error, Toast.LENGTH_LONG).show(); return }
            Toast.makeText(this, "Meminta ulang ${metric.title.lowercase()}…", Toast.LENGTH_SHORT).show()
        }
        if (MiFitnessHealthConnectImporter.isAvailable(this) &&
            (failures.containsKey("#import") || MiFitnessHealthConnectImporter.hasAnyReadPermission(this))
        ) {
            triggerMiFitnessImport(interactive = failures.containsKey("#import"))
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
            item.tvConnectionMode.text = when {
                paused -> "DirectSync sedang dijeda. Lanjutkan setelah selesai menggunakan Mi Fitness."
                ready -> "DirectSync aktif · jam terhubung"
                else -> "Jam belum terhubung ke DirectSync."
            }
            item.btnPauseMiFitness.text = if (paused) "Buka Mi Fitness" else "Jeda & buka Mi Fitness"
            item.btnPauseMiFitness.setIconResource(if (paused) R.drawable.ic_chevron_right else R.drawable.ic_pause)
            item.btnResumeDirectSync.visibility = if (paused) View.VISIBLE else View.GONE
            item.btnImportAllMiFitness.isEnabled = !sleepImportRunning
            item.btnImportAllMiFitness.text = if (sleepImportRunning) "Mengimpor riwayat…" else "Impor semua data Mi Fitness"
        }
        handoff(binding.miFitnessHandoff)
        syncCenterBinding?.let { content ->
            content.tvSyncCenterConnection.text = message
            handoff(content.handoff)
        }
        val embeddedTargets = mapOf(
            SyncMetric.HEART_RATE to binding.tvSyncRowHr,
            SyncMetric.STEPS to binding.tvSyncRowActivity,
            SyncMetric.SLEEP to binding.tvSyncRowSleep,
            SyncMetric.WORKOUTS to binding.tvSyncRowWorkout
        )
        embeddedTargets.forEach { (metric, tv) ->
            val entry = entries.getValue(metric)
            val evidence = viewModel.syncMetricEvidence.value[metric] ?: SyncMetricEvidence()
            val label = metricSyncLabel(entry.copy(inProgress = entry.inProgress || (metric == SyncMetric.SLEEP && sleepImportRunning)), evidence.complete)
            val statusWord = when (label) {
                MetricSyncLabel.COMPLETE -> "Lengkap"
                MetricSyncLabel.UNAVAILABLE -> "Belum tersedia"
                MetricSyncLabel.FAILED -> "Gagal"
                MetricSyncLabel.SYNCING -> "Menyinkronkan"
            }
            val color = ContextCompat.getColor(this, when (label) {
                MetricSyncLabel.COMPLETE -> R.color.primary_mint
                MetricSyncLabel.FAILED -> R.color.heart_rate_coral
                MetricSyncLabel.SYNCING -> R.color.secondary_cyan
                MetricSyncLabel.UNAVAILABLE -> R.color.on_surface_variant
            })
            tv.text = if (evidence.value.isNotEmpty() && evidence.value != "--") {
                "${evidence.value} · $statusWord"
            } else {
                statusWord
            }
            tv.setTextColor(color)
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
        if (bleScanInProgress) return
        val btManager = getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        if (btManager?.adapter?.isEnabled == false) {
            AlertDialog.Builder(this)
                .setTitle("Bluetooth Nonaktif")
                .setMessage("Aktifkan Bluetooth di HP terlebih dahulu untuk memindai Redmi Watch 3 di sekitar Anda.")
                .setPositiveButton("OK", null)
                .show()
            return
        }

        bleScanInProgress = true
        binding.btnScanBlePicker.isEnabled = false
        binding.btnScanBlePicker.alpha = 0.65f
        binding.btnScanBlePicker.text = "Memindai…"

        val intent = Intent(this, BleSyncService::class.java).apply {
            action = BleSyncService.ACTION_SCAN_DEVICES
        }
        ContextCompat.startForegroundService(this, intent)

        Toast.makeText(
            this,
            "Memindai perangkat BLE selama 4 detik...",
            Toast.LENGTH_SHORT
        ).show()

        blePickerScanJob?.cancel()
        blePickerScanJob = lifecycleScope.launch {
            try {
                delay(4_000)
                if (isFinishing || isDestroyed) return@launch
                val devices = viewModel.discoveredDevices.value
                if (devices.isEmpty()) {
                    AlertDialog.Builder(this@MainActivity)
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

                    AlertDialog.Builder(this@MainActivity)
                        .setTitle("Pilih Perangkat BLE Jam Tangan")
                        .setItems(items) { _, which ->
                            val chosen = devices[which]
                            viewModel.saveCredentialsAndWebhook(
                                mac = chosen.macAddress,
                                key = viewModel.authKey.value,
                                webhook = viewModel.webhookUrl.value
                            )
                            Toast.makeText(
                                this@MainActivity,
                                "MAC Address diatur ke: ${chosen.macAddress}",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                        .setNegativeButton("Batal", null)
                        .show()
                }
            } finally {
                bleScanInProgress = false
                if (!isFinishing && !isDestroyed) {
                    binding.btnScanBlePicker.isEnabled = true
                    binding.btnScanBlePicker.alpha = 1f
                    binding.btnScanBlePicker.text = "Pindai BLE"
                }
            }
        }
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
        if (Build.VERSION.SDK_INT >= 34 && MiFitnessHealthConnectImporter.hasAnyReadPermission(this)) {
            importMiFitnessHistory()
        }
        triggerSleepSync()
        bleService?.let { service ->
            service.queryBatteryStatus()
            service.requestLiveHeartRateMeasurement()
        }
        Toast.makeText(this, "Menyinkronkan aktivitas, tidur, baterai & sampel HR...", Toast.LENGTH_SHORT).show()
    }

    private fun triggerWebhookExport() {
        val url = viewModel.webhookUrl.value.trim()
        if (url.isEmpty() || url.toHttpUrlOrNull() == null) {
            Toast.makeText(
                this,
                "Atur URL Endpoint Webhook yang valid terlebih dahulu.",
                Toast.LENGTH_LONG
            ).show()
            showEditWebhookDialog()
            return
        }
        if (viewModel.unsyncedRecordsCount.value <= 0) {
            val mutedColor = ContextCompat.getColor(this, R.color.on_surface_muted)
            binding.tvWebhookLastAck.text = "● Tidak ada antrean belum push (0 rekaman)."
            binding.tvWebhookLastAck.setTextColor(mutedColor)
            Toast.makeText(this, "Semua rekaman sudah terkirim ke webhook.", Toast.LENGTH_SHORT).show()
            return
        }
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastWebhookDispatchAtMs < 1_500L) return
        lastWebhookDispatchAtMs = now

        val cyanColor = ContextCompat.getColor(this, R.color.secondary_cyan)
        binding.tvWebhookLastAck.text = "● Mengirim antrean ke webhook…"
        binding.tvWebhookLastAck.setTextColor(cyanColor)

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
        val dialogBinding = DialogEditWebhookBinding.inflate(layoutInflater)
        dialogBinding.etWebhookUrl.setText(viewModel.webhookUrl.value)
        dialogBinding.etWebhookUrl.doAfterTextChanged { dialogBinding.tilWebhookUrl.error = null }

        val dialog = MaterialAlertDialogBuilder(this)
            .setView(dialogBinding.root)
            .setPositiveButton("Simpan", null)
            .setNegativeButton("Batal", null)
            .create()

        dialog.window?.setBackgroundDrawable(
            ColorDrawable(ContextCompat.getColor(this, R.color.surface_container))
        )
        dialog.show()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val newUrl = dialogBinding.etWebhookUrl.text?.toString()?.trim().orEmpty()
            when {
                newUrl.isEmpty() -> {
                    dialogBinding.tilWebhookUrl.error = "Endpoint Webhook tidak boleh kosong"
                }
                !viewModel.saveWebhookUrl(newUrl) -> {
                    dialogBinding.tilWebhookUrl.error = "Gunakan URL valid dengan awalan http:// atau https://"
                }
                else -> {
                    Toast.makeText(this, "Webhook URL disimpan!", Toast.LENGTH_SHORT).show()
                    dialog.dismiss()
                }
            }
        }
    }

    private fun showCredentialsSettingsDialog() {
        val dialogBinding = DialogCredentialsSettingsBinding.inflate(layoutInflater)

        dialogBinding.switchAppNotifs.isChecked = Rw3NotificationListenerService.isNotificationForwardingEnabled(this)
        dialogBinding.switchCallNotifs.isChecked = Rw3NotificationListenerService.isCallForwardingEnabled(this)
        dialogBinding.switchWhitelistDialog.isChecked = Rw3NotificationListenerService.isWhitelistModeEnabled(this)
        dialogBinding.rowToggleAppNotifs.setOnClickListener { dialogBinding.switchAppNotifs.toggle() }
        dialogBinding.rowToggleCallNotifs.setOnClickListener { dialogBinding.switchCallNotifs.toggle() }
        dialogBinding.rowToggleWhitelistMode.setOnClickListener { dialogBinding.switchWhitelistDialog.toggle() }

        dialogBinding.etMacAddress.setText(viewModel.macAddress.value)
        dialogBinding.etAuthKey.setText(viewModel.authKey.value)
        dialogBinding.etWebhookCredentials.setText(viewModel.webhookUrl.value)

        dialogBinding.etMacAddress.doAfterTextChanged { dialogBinding.tilMacAddress.error = null }
        dialogBinding.etAuthKey.doAfterTextChanged { dialogBinding.tilAuthKey.error = null }
        dialogBinding.etWebhookCredentials.doAfterTextChanged { dialogBinding.tilWebhookCredentials.error = null }

        val dialog = MaterialAlertDialogBuilder(this)
            .setView(dialogBinding.root)
            .setPositiveButton("Simpan", null)
            .setNegativeButton("Batal", null)
            .create()

        dialog.window?.setBackgroundDrawable(
            ColorDrawable(ContextCompat.getColor(this, R.color.surface_container))
        )
        dialog.show()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val macInput = dialogBinding.etMacAddress.text?.toString().orEmpty()
            val keyInput = dialogBinding.etAuthKey.text?.toString().orEmpty()
            val webhookInput = dialogBinding.etWebhookCredentials.text?.toString().orEmpty()

            val result = viewModel.saveCredentialsAndWebhook(
                mac = macInput,
                key = keyInput,
                webhook = webhookInput
            )
            result.fold(
                onSuccess = {
                    Rw3NotificationListenerService.setNotificationForwardingEnabled(
                        this,
                        dialogBinding.switchAppNotifs.isChecked
                    )
                    Rw3NotificationListenerService.setCallForwardingEnabled(
                        this,
                        dialogBinding.switchCallNotifs.isChecked
                    )
                    Rw3NotificationListenerService.setWhitelistModeEnabled(
                        this,
                        dialogBinding.switchWhitelistDialog.isChecked
                    )
                    updateNotificationUiState()
                    updateGroupedMenuSubtitles()
                    Toast.makeText(this, "Pengaturan berhasil disimpan!", Toast.LENGTH_SHORT).show()
                    dialog.dismiss()
                },
                onFailure = { error ->
                    val msg = error.message ?: "Periksa kembali format input"
                    when {
                        msg.contains("MAC", ignoreCase = true) -> dialogBinding.tilMacAddress.error = msg
                        msg.contains("Webhook", ignoreCase = true) -> dialogBinding.tilWebhookCredentials.error = msg
                        else -> dialogBinding.tilAuthKey.error = msg
                    }
                }
            )
        }
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
            holder.binding.cbWhitelisted.contentDescription = "Izinkan notifikasi ${item.appName}"
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
                holder.binding.cbWhitelisted.toggle()
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
            val loadingVisible = dialogBinding.layoutLoading.visibility == View.VISIBLE
            dialogBinding.tvEmptySearchApps.visibility = if (!loadingVisible && showingCount == 0) View.VISIBLE else View.GONE
            dialogBinding.rvApps.visibility = if (!loadingVisible && showingCount > 0) View.VISIBLE else View.GONE
        }

        dialogBinding.rvApps.layoutManager = LinearLayoutManager(this)

        var scanJob: Job? = null

        val alertDialog = BottomSheetDialog(this)
        alertDialog.setContentView(dialogBinding.root)
        dialogBinding.btnSaveFilter.isEnabled = false
        listOf(dialogBinding.btnPresetChat, dialogBinding.btnSelectAll, dialogBinding.btnClearAll)
            .forEach { it.isEnabled = false }
        dialogBinding.btnSaveFilter.setOnClickListener {
                val selected = adapter?.getSelectedPackages() ?: initialWhitelist
                val whitelistActive = dialogBinding.switchWhitelistMode.isChecked
                Rw3NotificationListenerService.setWhitelistModeEnabled(this, whitelistActive)
                Rw3NotificationListenerService.setWhitelistedPackages(this, selected)
                updateNotificationUiState()
                updateGroupedMenuSubtitles()
                Toast.makeText(
                    this,
                    if (whitelistActive) {
                        "Filter Whitelist Aktif: ${selected.size} aplikasi diizinkan."
                    } else {
                        "Filter Whitelist dinonaktifkan (semua aplikasi diizinkan)."
                    },
                    Toast.LENGTH_SHORT
                ).show()
                alertDialog.dismiss()
        }
        dialogBinding.btnCancelFilter.setOnClickListener { alertDialog.dismiss() }
        alertDialog.setOnDismissListener { scanJob?.cancel() }
        alertDialog.setOnShowListener {
            alertDialog.window?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
            val sheet = alertDialog.findViewById<FrameLayout>(com.google.android.material.R.id.design_bottom_sheet)
                ?: return@setOnShowListener
            sheet.setBackgroundResource(R.drawable.bg_app_filter_sheet)
            val density = resources.displayMetrics.density
            val maxHeight = minOf((resources.displayMetrics.heightPixels * 0.9f).toInt(), (760 * density).toInt())
            sheet.layoutParams.height = maxHeight
            BottomSheetBehavior.from(sheet).apply {
                state = BottomSheetBehavior.STATE_EXPANDED
                skipCollapsed = true
                isDraggable = false
            }
            // The sheet can consume IME insets before its content receives them.
            sheet.viewTreeObserver.addOnGlobalLayoutListener {
                val visible = android.graphics.Rect()
                sheet.getWindowVisibleDisplayFrame(visible)
                val height = minOf(maxHeight, visible.height())
                if (height > 0 && sheet.layoutParams.height != height) {
                    sheet.layoutParams = sheet.layoutParams.apply { this.height = height }
                }
            }
        }
        alertDialog.show()

        // Asynchronously scan installed applications without blocking UI
        scanJob = lifecycleScope.launch(Dispatchers.Default) {
            val pm = packageManager
            val mainIntent = Intent(Intent.ACTION_MAIN, null).apply {
                addCategory(Intent.CATEGORY_LAUNCHER)
            }
            val resolveInfos = runCatching { pm.queryIntentActivities(mainIntent, 0) }.getOrDefault(emptyList())
            val seen = HashSet<String>()
            val items = mutableListOf<WhitelistAppItem>()

            for (ri in resolveInfos) {
                val pkg = ri.activityInfo?.packageName ?: continue
                if (pkg != packageName && seen.add(pkg)) {
                    val label = runCatching { ri.loadLabel(pm).toString() }.getOrDefault(pkg.substringAfterLast('.'))
                    val icon = runCatching { ri.loadIcon(pm) }.getOrNull()
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
                adapter?.filter(dialogBinding.etSearchApp.text?.toString().orEmpty())
                dialogBinding.layoutLoading.visibility = View.GONE
                dialogBinding.btnSaveFilter.isEnabled = true
                listOf(dialogBinding.btnPresetChat, dialogBinding.btnSelectAll, dialogBinding.btnClearAll)
                    .forEach { it.isEnabled = true }
                updateCountDisplay()
                updateNotificationUiState()

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
