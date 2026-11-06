package com.rw3.directsync.ble

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothSocket
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.view.KeyEvent
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.rw3.directsync.ble.parser.XiaomiActivityTransfer
import com.rw3.directsync.ble.parser.XiaomiActivityFileParser
import com.rw3.directsync.ble.parser.XiaomiSleepFileParser
import com.rw3.directsync.ble.protocol.XiaomiWearProtocolEngine
import com.rw3.directsync.data.db.Rw3Database
import com.rw3.directsync.data.db.entity.HeartRateEntity
import com.rw3.directsync.network.WebhookDispatcher
import com.rw3.directsync.notification.Rw3NotificationListenerService
import com.rw3.directsync.ui.MainActivity
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.lang.reflect.Method
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import kotlin.random.Random

/**
 * Dual-Transport (Bluetooth Classic RFCOMM/SPP + BLE GATT) Foreground Service for Redmi Watch 3 (`lchz.watch.m65sa`).
 *
 * Implements the official Gadgetbridge `XiaomiSupport` / `RedmiWatch3Coordinator` architecture with Eco Power Management:
 * 1. Connects simultaneously via RFCOMM SPP (`00001101-0000-1000-8000-00805f9b34fb`) and BLE GATT (`0000fdab`).
 * 2. Performs the 4-step Xiaomi Wear Protobuf Authentication (`type=1, subtype=26 -> 27`) using
 *    HKDF-HMAC-SHA256 (`"miwear-auth"`) + AES-CCM / AES-CTR with the user's 32-char Auth Key.
 * 3. Uses Smart Burst Cut-Off (`subtype=45` START -> immediate `subtype=46` STOP after receiving BPM)
 *    and On-Demand HR Streaming so the watch's optical PPG sensor and Bluetooth radio are not left running 24/7.
 * 4. Sends `Command(type=8, subtype=1/2/3)` to fetch recorded Sleep & Activity files.
 */
@SuppressLint("MissingPermission")
class BleSyncService : LifecycleService() {

    companion object {
        private const val TAG = "BleSyncService"

        @Volatile
        var instance: BleSyncService? = null
            private set

        private const val NOTIFICATION_CHANNEL_ID = "rw3_ble_sync_channel"
        private const val NOTIFICATION_ID = 1001

        const val ACTION_START_SYNC = "com.rw3.directsync.action.START_SYNC"
        const val ACTION_STOP_SYNC = "com.rw3.directsync.action.STOP_SYNC"
        const val ACTION_SCAN_DEVICES = "com.rw3.directsync.action.SCAN_DEVICES"
        const val ACTION_START_HEART_RATE = "com.rw3.directsync.action.START_HEART_RATE"
        const val ACTION_STOP_HEART_RATE = "com.rw3.directsync.action.STOP_HEART_RATE"
        const val ACTION_TOGGLE_HEART_RATE = "com.rw3.directsync.action.TOGGLE_HEART_RATE"
        const val ACTION_SYNC_SLEEP = "com.rw3.directsync.action.SYNC_SLEEP"
        const val ACTION_EXPORT_WEBHOOK = "com.rw3.directsync.action.EXPORT_WEBHOOK"
        const val ACTION_SEND_TEST_NOTIFICATION = "com.rw3.directsync.action.SEND_TEST_NOTIFICATION"
        const val ACTION_MEDIA_PLAY = "com.rw3.directsync.action.MEDIA_PLAY"
        const val ACTION_MEDIA_PAUSE = "com.rw3.directsync.action.MEDIA_PAUSE"
        const val ACTION_MEDIA_NEXT = "com.rw3.directsync.action.MEDIA_NEXT"
        const val ACTION_MEDIA_PREV = "com.rw3.directsync.action.MEDIA_PREV"

        const val EXTRA_MAC_ADDRESS = "extra_mac_address"
        const val EXTRA_AUTH_KEY = "extra_auth_key"
        const val EXTRA_WEBHOOK_URL = "extra_webhook_url"
        const val EXTRA_SLEEP_SINCE_MILLIS = "extra_sleep_since_millis"

        private const val SCAN_TIMEOUT_MS = 8_000L
        private const val AUTO_CONNECT_TIMEOUT_MS = 20_000L
        private const val HR_POLL_INTERVAL_MS = 15_000L
        private const val HR_SENSOR_CUTOFF_TIMEOUT_MS = 8_000L
        private const val BATTERY_POLL_INTERVAL_MS = 30 * 60 * 1000L // 30 minutes to save watch battery
        private const val MIN_RECONNECT_DELAY_MS = 3_000L
        private const val MAX_RECONNECT_DELAY_MS = 120_000L // 2 minutes max exponential backoff
        private const val WATCHDOG_INTERVAL_MS = 300_000L // 5 minutes periodic watchdog

        val UUID_SPP = UUID.fromString("00001101-0000-1000-8000-00805f9b34fb")
        val UUID_SERVICE_FDAB = UUID.fromString("0000fdab-0000-1000-8000-00805f9b34fb")
        val UUID_CHAR_FDAB_VER = UUID.fromString("00000001-0000-1000-8000-00805f9b34fb")
        val UUID_CHAR_FDAB_TX = UUID.fromString("00000002-0000-1000-8000-00805f9b34fb")
        val UUID_CHAR_FDAB_RX = UUID.fromString("00000003-0000-1000-8000-00805f9b34fb")
    }

    data class DiscoveredBleDevice(
        val name: String,
        val macAddress: String,
        val rssi: Int,
        val isBonded: Boolean
    )

    sealed class SyncState {
        data object Idle : SyncState()
        data class Connecting(val statusMessage: String) : SyncState()
        data object DiscoveringServices : SyncState()
        data object Authenticating : SyncState()
        data object Authenticated : SyncState()
        data class StreamingHeartRate(val latestBpm: Int) : SyncState()
        data class SyncingSleep(val bytesBuffered: Int) : SyncState()
        data class SleepSyncCompleted(val stagesSaved: Int) : SyncState()
        data class WebhookDispatched(val hrCount: Int, val sleepCount: Int, val activityCount: Int = 0) : SyncState()
        data class Disconnected(val reason: String, val willReconnect: Boolean = false) : SyncState()
        data class Error(val message: String) : SyncState()
    }

    private sealed class GattOperation {
        data class RequestMtu(val mtu: Int) : GattOperation()
        data class EnableNotification(
            val serviceUuid: UUID,
            val characteristicUuid: UUID,
            val useIndication: Boolean = false
        ) : GattOperation()

        data class WriteCharacteristic(
            val serviceUuid: UUID,
            val characteristicUuid: UUID,
            val payload: ByteArray,
            val writeType: Int = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        ) : GattOperation()

        data class ReadCharacteristic(
            val serviceUuid: UUID,
            val characteristicUuid: UUID
        ) : GattOperation()
    }

    private val binder = LocalBinder()
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
    private val mainHandler = Handler(Looper.getMainLooper())
    private val timeFormatter = SimpleDateFormat("HH:mm:ss", Locale.US)

    private val db by lazy { Rw3Database.getDatabase(this) }
    private val webhookDispatcher by lazy { WebhookDispatcher(this) }

    private var bluetoothManager: BluetoothManager? = null
    private var bluetoothAdapter: BluetoothAdapter? = null
    private var bluetoothGatt: BluetoothGatt? = null
    private var bluetoothScanner: BluetoothLeScanner? = null

    // RFCOMM SPP Socket & Stream State
    private var sppSocket: BluetoothSocket? = null
    private var sppOutputStream: OutputStream? = null
    private var sppReaderJob: Job? = null
    @Volatile
    private var sppProtocolVersion: Int = 1 // 1 = BA DC FE, 2 = A5 A5

    // Xiaomi Wear Protobuf & Crypto Engine
    private var protocolEngine: XiaomiWearProtocolEngine? = null

    private var targetMacAddress: String? = null
    private var targetAuthKeyHex: String? = null
    private var targetWebhookUrl: String? = null
    private var userRequestedStop: Boolean = false

    private var activeCommandServiceUuid: UUID? = null
    private var activeCommandCharUuid: UUID? = null

    private var scanCallback: ScanCallback? = null
    private var scanTimeoutRunnable: Runnable? = null
    private var connectWatchdogRunnable: Runnable? = null
    private var reconnectJob: Job? = null
    private var reconnectAttempt: Int = 0

    private val gattOperationQueue = Channel<GattOperation>(Channel.UNLIMITED)
    private var gattOperationCompletionSignal = Channel<Boolean>(Channel.CONFLATED)

    // Chunked BLE SAR reconstruction state
    private var expectedBleChunks: Int = 0
    private val receivedBleChunks = linkedMapOf<Int, ByteArray>()

    // Activity / Sleep File Fetching Buffer
    private val activityTransfer = XiaomiActivityTransfer()
    private var activeActivityFileId: ByteArray? = null
    private val seenActivityFileIds = mutableSetOf<String>()
    private var requestedAlternateActivityList = false
    private var receivedSleepFile = false
    private val activityHandler = Handler(Looper.getMainLooper())
    private val activityTimeout = Runnable {
        appendLog("Unduhan file tidur melewati batas waktu; file tidak disimpan.")
        activeActivityFileId = null
        activityTransfer.reset()
        requestNextActivityFile()
    }
    private val pendingActivityFileIds = ArrayDeque<ByteArray>()

    private val _syncState = MutableStateFlow<SyncState>(SyncState.Idle)
    val syncState: StateFlow<SyncState> = _syncState.asStateFlow()

    private val _heartRateBpm = MutableStateFlow<Int?>(null)
    val heartRateBpm: StateFlow<Int?> = _heartRateBpm.asStateFlow()

    private val _isHrStreaming = MutableStateFlow(false)
    val isHrStreaming: StateFlow<Boolean> = _isHrStreaming.asStateFlow()

    private val _batteryLevel = MutableStateFlow<Int?>(null)
    val batteryLevel: StateFlow<Int?> = _batteryLevel.asStateFlow()

    data class MediaPlaybackInfo(
        val title: String = "",
        val artist: String = "",
        val isPlaying: Boolean = false,
        val volumePercent: Int = 0,
        val positionSeconds: Int = 0,
        val durationSeconds: Int = 0
    )

    private val _currentMediaState = MutableStateFlow(MediaPlaybackInfo())
    val currentMediaState: StateFlow<MediaPlaybackInfo> = _currentMediaState.asStateFlow()

    private val _discoveredDevices = MutableStateFlow<List<DiscoveredBleDevice>>(emptyList())
    val discoveredDevices: StateFlow<List<DiscoveredBleDevice>> = _discoveredDevices.asStateFlow()

    private val _diagnosticLogs = MutableStateFlow<List<String>>(emptyList())
    val diagnosticLogs: StateFlow<List<String>> = _diagnosticLogs.asStateFlow()

    fun clearDiagnosticLogs() {
        _diagnosticLogs.value = emptyList()
    }

    inner class LocalBinder : Binder() {
        fun getService(): BleSyncService = this@BleSyncService
    }

    override fun onBind(intent: Intent): IBinder {
        super.onBind(intent)
        return binder
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        bluetoothAdapter = bluetoothManager?.adapter
        createNotificationChannel()
        startGattOperationProcessor()
        setupMediaController()
        registerAutoReconnectReceivers()
        startBackgroundWatchdog()
        appendLog("BleSyncService siap (Eco Power Mode & Auto-Reconnect Cerdas aktif).")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_START_SYNC -> {
                val mac = intent.getStringExtra(EXTRA_MAC_ADDRESS)?.trim()?.uppercase()
                val authKey = intent.getStringExtra(EXTRA_AUTH_KEY)?.trim()
                targetWebhookUrl = intent.getStringExtra(EXTRA_WEBHOOK_URL)?.trim()

                if (mac.isNullOrEmpty() || authKey.isNullOrEmpty()) {
                    _syncState.value = SyncState.Error("MAC Address dan 32-char Auth Key diperlukan.")
                    return START_NOT_STICKY
                }

                try {
                    protocolEngine = XiaomiWearProtocolEngine(authKey)
                } catch (e: IllegalArgumentException) {
                    _syncState.value = SyncState.Error(e.message ?: "Auth Key tidak valid")
                    return START_NOT_STICKY
                }

                targetMacAddress = mac
                targetAuthKeyHex = authKey
                userRequestedStop = false
                reconnectAttempt = 0

                promoteToForeground("Menghubungkan ke Redmi Watch 3 ($mac)...")
                appendLog("=== Memulai Koneksi ke: $mac ===")
                initiateSmartConnect(mac, strategyIndex = 1)
            }

            ACTION_START_HEART_RATE -> {
                startHeartRateStream()
            }

            ACTION_STOP_HEART_RATE -> {
                stopHeartRateStream()
            }

            ACTION_TOGGLE_HEART_RATE -> {
                toggleHeartRateStream()
            }

            ACTION_SCAN_DEVICES -> {
                promoteToForeground("Memindai perangkat BLE terdekat...")
                startDiscoveryScanOnly()
            }

            ACTION_STOP_SYNC -> {
                appendLog("Layanan dihentikan oleh pengguna.")
                userRequestedStop = true
                stopHeartRateStream(silent = true)
                batteryMonitorJob?.cancel()
                stopScan()
                cancelConnectWatchdog()
                reconnectJob?.cancel()
                watchdogJob?.cancel()
                unregisterAutoReconnectReceivers()
                cleanupMediaController()
                closeSppSocket()
                disconnectAndCloseGatt()
                _syncState.value = SyncState.Idle
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }

            ACTION_SYNC_SLEEP -> {
                val since = intent.getLongExtra(
                    EXTRA_SLEEP_SINCE_MILLIS,
                    System.currentTimeMillis() - 24 * 3600 * 1000L
                )
                triggerSleepDataSync(since)
            }

            ACTION_EXPORT_WEBHOOK -> {
                val url = intent.getStringExtra(EXTRA_WEBHOOK_URL) ?: targetWebhookUrl
                if (!url.isNullOrEmpty()) {
                    triggerWebhookExport(url)
                } else {
                    _syncState.value = SyncState.Error("URL Webhook belum ditentukan.")
                }
            }

            ACTION_SEND_TEST_NOTIFICATION -> {
                forwardNotification(
                    id = 12345,
                    packageName = "com.rw3.directsync",
                    appName = "RW3-DirectSync",
                    title = "Tes Notifikasi Berhasil! 🎉",
                    body = "Notifikasi dari HP berhasil masuk dengan cepat & responsif ke Redmi Watch 3."
                )
            }

            ACTION_MEDIA_PLAY -> sendMediaPlay()
            ACTION_MEDIA_PAUSE -> sendMediaPause()
            ACTION_MEDIA_NEXT -> sendMediaNext()
            ACTION_MEDIA_PREV -> sendMediaPrevious()
        }
        return START_REDELIVER_INTENT
    }

    override fun onDestroy() {
        if (instance === this) {
            instance = null
        }
        activityHandler.removeCallbacksAndMessages(null)
        mainHandler.removeCallbacks(hrSensorCutOffRunnable)
        userRequestedStop = true
        stopWatchHrSensorHardware("Service ditutup", forceSend = false)
        hrPollingJob?.cancel()
        batteryMonitorJob?.cancel()
        stopScan()
        cancelConnectWatchdog()
        reconnectJob?.cancel()
        watchdogJob?.cancel()
        unregisterAutoReconnectReceivers()
        cleanupMediaController()
        gattOperationQueue.close()
        closeSppSocket()
        disconnectAndCloseGatt()
        super.onDestroy()
    }

    private fun appendLog(message: String) {
        Log.i(TAG, message)
        val timestamped = "[${timeFormatter.format(Date())}] $message"
        val current = _diagnosticLogs.value
        _diagnosticLogs.value = (current + timestamped).takeLast(120)
    }

    // =========================================================================
    // Discovery Scan Mode
    // =========================================================================

    fun startDiscoveryScanOnly() {
        lifecycleScope.launch(Dispatchers.Main) {
            val adapter = bluetoothAdapter
            if (adapter == null || !adapter.isEnabled) {
                appendLog("ERROR: Bluetooth adapter tidak aktif.")
                return@launch
            }

            stopScan()
            val foundMap = linkedMapOf<String, DiscoveredBleDevice>()

            adapter.bondedDevices?.forEach { bonded ->
                val dName = bonded.name ?: "Bonded Device"
                foundMap[bonded.address] = DiscoveredBleDevice(
                    name = dName,
                    macAddress = bonded.address,
                    rssi = 0,
                    isBonded = true
                )
                if (bonded.address.equals(targetMacAddress, ignoreCase = true) || dName.contains("Redmi", ignoreCase = true)) {
                    appendLog("Perangkat terpasang di HP: $dName [${bonded.address}]")
                }
            }

            _discoveredDevices.value = foundMap.values.toList()
            appendLog("Memindai sinyal BLE sekitar...")
            _syncState.value = SyncState.Connecting("Memindai sinyal BLE...")

            bluetoothScanner = adapter.bluetoothLeScanner
            val scanner = bluetoothScanner ?: return@launch

            val settings = ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .build()

            scanCallback = object : ScanCallback() {
                override fun onScanResult(callbackType: Int, result: ScanResult?) {
                    val dev = result?.device ?: return
                    val name = dev.name ?: result.scanRecord?.deviceName ?: ""
                    val mac = dev.address
                    if (name.isNotEmpty() || mac.equals(targetMacAddress, ignoreCase = true)) {
                        val display = name.ifEmpty { "BLE Device" }
                        foundMap[mac] = DiscoveredBleDevice(
                            name = display,
                            macAddress = mac,
                            rssi = result.rssi,
                            isBonded = dev.bondState == BluetoothDevice.BOND_BONDED
                        )
                        _discoveredDevices.value = foundMap.values.toList()
                    }
                }
            }

            scanTimeoutRunnable = Runnable {
                stopScan()
                appendLog("Pindai selesai: ${foundMap.size} perangkat terdeteksi.")
                _syncState.value = SyncState.Idle
            }
            mainHandler.postDelayed(scanTimeoutRunnable!!, SCAN_TIMEOUT_MS)

            try {
                scanner.startScan(null, settings, scanCallback)
            } catch (e: Exception) {
                appendLog("Gagal scan: ${e.message}")
            }
        }
    }

    // =========================================================================
    // Dual-Mode Connection (RFCOMM SPP + BLE GATT)
    // =========================================================================

    private fun initiateSmartConnect(macAddress: String, strategyIndex: Int) {
        lifecycleScope.launch(Dispatchers.Main) {
            val adapter = bluetoothAdapter
            if (adapter == null || !adapter.isEnabled) {
                _syncState.value = SyncState.Error("Bluetooth mati.")
                return@launch
            }

            if (!BluetoothAdapter.checkBluetoothAddress(macAddress)) {
                _syncState.value = SyncState.Error("Format MAC address tidak valid: $macAddress")
                return@launch
            }

            stopScan()
            cancelConnectWatchdog()
            closeDeadGattOnly()
            delay(350)

            val bondedMatch = adapter.bondedDevices?.firstOrNull {
                it.address.equals(macAddress, ignoreCase = true) ||
                    (it.name?.contains("Redmi Watch", ignoreCase = true) == true)
            }

            val deviceToConnect = bondedMatch ?: adapter.getRemoteDevice(macAddress)

            // 1. Launch parallel Bluetooth Classic RFCOMM / SPP connection (primary Xiaomi Wear transport on RW3)
            connectRfcommSppAsync(deviceToConnect)

            // 2. Also connect BLE GATT (TRANSPORT_AUTO)
            executeConnectStrategy(deviceToConnect, strategyIndex)
        }
    }

    /**
     * Connects to Redmi Watch 3's Serial Port Profile (`00001101-0000-1000-8000-00805f9b34fb`),
     * which is the primary channel used by Xiaomi Wear / Gadgetbridge on `RedmiWatch3Coordinator`
     * when BLE only exposes `0000fdab`.
     */
    private fun connectRfcommSppAsync(device: BluetoothDevice) {
        sppReaderJob?.cancel()
        sppReaderJob = lifecycleScope.launch(ioDispatcher) {
            closeSppSocket()
            appendLog("Membuka kanal serial SPP ke ${device.address}...")

            var socket: BluetoothSocket? = null
            try {
                socket = device.createRfcommSocketToServiceRecord(UUID_SPP)
                socket.connect()
            } catch (e1: Exception) {
                try {
                    socket?.close()
                    socket = device.createInsecureRfcommSocketToServiceRecord(UUID_SPP)
                    socket.connect()
                } catch (e2: Exception) {
                    try {
                        socket?.close()
                        val m = device.javaClass.getMethod("createRfcommSocket", Int::class.javaPrimitiveType)
                        socket = m.invoke(device, 1) as BluetoothSocket
                        socket.connect()
                    } catch (e3: Exception) {
                        appendLog("Kanal SPP tidak aktif, menggunakan jalur BLE GATT.")
                        return@launch
                    }
                }
            }

            if (socket == null || !socket.isConnected) return@launch

            sppSocket = socket
            sppOutputStream = socket.outputStream
            appendLog("Terhubung ke kanal serial SPP!")
            _syncState.value = SyncState.Authenticating
            updateForegroundNotification("Terhubung via SPP! Memulai Handshake Xiaomi Wear...")

            val engine = protocolEngine ?: return@launch
            engine.resetCounters()
            sppProtocolVersion = 1

            // Step 0: Send SPP V1 Version Request (BA DC FE 00 C0 03 00 00 00 00 EF)
            val versionReq = engine.buildSppV1VersionRequest()
            writeSppBytes("SPP Version Request", versionReq)

            // Also schedule fallback Auth Step 1 if watch doesn't reply to version within 1.5s
            launch {
                delay(1500)
                if (sppSocket?.isConnected == true && !engine.isEncryptionInitialized) {
                    sendXiaomiAuthStep1("SPP Fallback")
                }
            }

            // Continuous SPP Read Loop
            val inputStream: InputStream = socket.inputStream
            val readBuf = ByteArray(2048)
            val streamAcc = ByteArrayOutputStream()

            try {
                while (isActive && socket.isConnected) {
                    val bytesRead = inputStream.read(readBuf)
                    if (bytesRead <= 0) break
                    val chunk = readBuf.copyOfRange(0, bytesRead)
                    streamAcc.write(chunk)
                    val remaining = processIncomingSppStream(streamAcc.toByteArray())
                    streamAcc.reset()
                    if (remaining.isNotEmpty()) {
                        streamAcc.write(remaining)
                    }
                }
            } catch (e: Exception) {
                if (!userRequestedStop) {
                    appendLog("Koneksi SPP terputus: ${e.message}")
                }
            } finally {
                handleSppConnectionLost(socket)
            }
        }
    }

    private fun handleSppConnectionLost(failedSocket: BluetoothSocket?) {
        mainHandler.post {
            // A stale reader must never close a newer connection.
            if (failedSocket == null || sppSocket !== failedSocket) return@post
            hrPollingJob?.cancel()
            batteryMonitorJob?.cancel()
            mainHandler.removeCallbacks(hrSensorCutOffRunnable)
            isWatchHrSensorActive = false
            _isHrStreaming.value = false
            closeSppSocket()
            activityHandler.removeCallbacks(activityTimeout)
            activityTransfer.reset()
            activeActivityFileId = null
            if (!userRequestedStop) {
                _syncState.value = SyncState.Disconnected("Koneksi serial terputus", willReconnect = true)
                scheduleReconnect("Koneksi serial terputus")
            }
        }
    }

    private fun writeSppBytes(label: String, bytes: ByteArray): Boolean {
        val out = sppOutputStream ?: return false
        return try {
            synchronized(out) {
                out.write(bytes)
                out.flush()
            }
            true
        } catch (e: Exception) {
            appendLog("Error kirim SPP ($label): ${e.message}")
            handleSppConnectionLost(sppSocket)
            false
        }
    }

    private fun closeSppSocket() {
        try {
            sppOutputStream?.close()
        } catch (_: Exception) {}
        try {
            sppSocket?.close()
        } catch (_: Exception) {}
        sppOutputStream = null
        sppSocket = null
    }

    private fun executeConnectStrategy(device: BluetoothDevice, strategyIndex: Int) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { executeConnectStrategy(device, strategyIndex) }
            return
        }

        val modeIndex = strategyIndex % 4
        val strategyName = when (modeIndex) {
            1 -> "TRANSPORT_AUTO / Dual-Mode"
            2 -> "TRANSPORT_LE Passive (autoConnect=true)"
            3 -> "Legacy connectGatt"
            else -> "TRANSPORT_LE (autoConnect=false)"
        }

        _syncState.value = SyncState.Connecting("Menghubungkan via $strategyName...")
        appendLog("Menghubungkan ke ${device.address} via $strategyName (bondState=${device.bondState})...")

        cancelConnectWatchdog()

        bluetoothGatt = when (modeIndex) {
            1 -> device.connectGatt(
                this@BleSyncService,
                false,
                gattCallback,
                BluetoothDevice.TRANSPORT_AUTO
            )
            2 -> {
                connectWatchdogRunnable = Runnable {
                    appendLog("Watchdog timeout autoConnect; beralih strategi...")
                    closeDeadGattOnly()
                    scheduleReconnect("Timeout autoConnect")
                }
                mainHandler.postDelayed(connectWatchdogRunnable!!, AUTO_CONNECT_TIMEOUT_MS)
                device.connectGatt(
                    this@BleSyncService,
                    true,
                    gattCallback,
                    BluetoothDevice.TRANSPORT_LE
                )
            }
            3 -> {
                @Suppress("DEPRECATION")
                device.connectGatt(this@BleSyncService, false, gattCallback)
            }
            else -> device.connectGatt(
                this@BleSyncService,
                false,
                gattCallback,
                BluetoothDevice.TRANSPORT_LE
            )
        }
    }

    private fun stopScan() {
        scanTimeoutRunnable?.let { mainHandler.removeCallbacks(it) }
        scanTimeoutRunnable = null
        try {
            scanCallback?.let { bluetoothScanner?.stopScan(it) }
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping scan", e)
        }
        scanCallback = null
    }

    private fun cancelConnectWatchdog() {
        connectWatchdogRunnable?.let { mainHandler.removeCallbacks(it) }
        connectWatchdogRunnable = null
    }

    private var autoReconnectReceiver: BroadcastReceiver? = null
    private var watchdogJob: Job? = null

    private fun registerAutoReconnectReceivers() {
        if (autoReconnectReceiver != null) return
        val filter = IntentFilter().apply {
            addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
            addAction(Intent.ACTION_USER_PRESENT)
            addAction(Intent.ACTION_SCREEN_ON)
        }
        autoReconnectReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                when (intent?.action) {
                    BluetoothAdapter.ACTION_STATE_CHANGED -> {
                        val state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)
                        if (state == BluetoothAdapter.STATE_ON) {
                            appendLog("[Auto-Reconnect Cerdas] Bluetooth HP diaktifkan, memicu koneksi cepat...")
                            if (!userRequestedStop && targetMacAddress != null && _syncState.value !is SyncState.Authenticated) {
                                scheduleReconnect("Bluetooth HP On", immediate = true)
                            }
                        }
                    }
                    Intent.ACTION_USER_PRESENT, Intent.ACTION_SCREEN_ON -> {
                        if (!userRequestedStop && targetMacAddress != null && _syncState.value is SyncState.Disconnected && reconnectAttempt > 2) {
                            appendLog("[Auto-Reconnect Cerdas] Layar HP menyala (User Present), mencoba reconnect oportunistik...")
                            scheduleReconnect("Layar Menyala (Opportunistic)", immediate = true)
                        }
                    }
                }
            }
        }
        try {
            registerReceiver(autoReconnectReceiver, filter)
        } catch (e: Exception) {
            Log.w(TAG, "Gagal mendaftarkan autoReconnectReceiver: ${e.message}")
        }
    }

    private fun unregisterAutoReconnectReceivers() {
        try {
            if (autoReconnectReceiver != null) {
                unregisterReceiver(autoReconnectReceiver)
                autoReconnectReceiver = null
            }
        } catch (_: Exception) {}
    }

    private fun startBackgroundWatchdog() {
        watchdogJob?.cancel()
        watchdogJob = lifecycleScope.launch(Dispatchers.IO) {
            while (isActive) {
                delay(WATCHDOG_INTERVAL_MS)
                if (!userRequestedStop && targetMacAddress != null && _syncState.value is SyncState.Disconnected) {
                    withContext(Dispatchers.Main) {
                        appendLog("[Watchdog] Pengecekan latar belakang: jam belum tersambung, memicu reconnect...")
                        scheduleReconnect("Periodic Watchdog", immediate = false)
                    }
                }
            }
        }
    }

    private fun calculateReconnectDelayMs(attempt: Int): Long {
        if (attempt <= 1) return MIN_RECONNECT_DELAY_MS
        val baseDelay = (MIN_RECONNECT_DELAY_MS * Math.pow(1.8, (attempt - 1).coerceAtMost(6).toDouble())).toLong()
        val jitter = Random.nextLong(500L, 2000L)
        return (baseDelay + jitter).coerceIn(MIN_RECONNECT_DELAY_MS, MAX_RECONNECT_DELAY_MS)
    }

    private fun scheduleReconnect(reason: String, immediate: Boolean = false) {
        if (userRequestedStop) return
        if (sppSocket?.isConnected == true) {
            appendLog("GATT terputus ($reason), namun koneksi SPP masih aktif.")
            return
        }
        val mac = targetMacAddress ?: return

        reconnectJob?.cancel()
        reconnectJob = lifecycleScope.launch(Dispatchers.Main) {
            if (immediate) {
                reconnectAttempt = 1
            } else {
                reconnectAttempt++
            }
            val delayMs = if (immediate) 500L else calculateReconnectDelayMs(reconnectAttempt)
            val delaySec = delayMs / 1000
            val nextStrategy = if (reconnectAttempt % 4 == 0) 1 else reconnectAttempt % 4

            _syncState.value = SyncState.Disconnected("$reason -> Reconnecting in ${delaySec}s (#$reconnectAttempt)", willReconnect = true)
            updateForegroundNotification("Terputus ($reason) • Reconnecting dalam ${delaySec}s (#$reconnectAttempt)...")
            appendLog("[Auto-Reconnect Cerdas] Reconnect #$reconnectAttempt via strategi #$nextStrategy dalam ${delaySec}s...")

            delay(delayMs)
            if (!userRequestedStop && _syncState.value !is SyncState.Authenticated) {
                initiateSmartConnect(mac, strategyIndex = nextStrategy)
            }
        }
    }

    // =========================================================================
    // Two-Way Media / Music Control (MediaSessionManager + Xiaomi Wear Protobuf)
    // =========================================================================

    private var mediaSessionManager: MediaSessionManager? = null
    private var activeMediaController: MediaController? = null
    private var audioManager: AudioManager? = null

    private val activeSessionsListener = MediaSessionManager.OnActiveSessionsChangedListener { controllers ->
        updateActiveMediaController(controllers)
    }

    private val mediaControllerCallback = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadata?) {
            syncCurrentMediaToWatch()
        }

        override fun onPlaybackStateChanged(state: PlaybackState?) {
            syncCurrentMediaToWatch()
        }
    }

    private fun setupMediaController() {
        try {
            audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            mediaSessionManager = getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager

            val notifListenerComponent = ComponentName(this, Rw3NotificationListenerService::class.java)
            if (Rw3NotificationListenerService.isNotificationAccessGranted(this)) {
                val sessions = mediaSessionManager?.getActiveSessions(notifListenerComponent)
                updateActiveMediaController(sessions)
                mediaSessionManager?.addOnActiveSessionsChangedListener(activeSessionsListener, notifListenerComponent)
                appendLog("[Kontrol Musik] MediaSession listener terpasang (${sessions?.size ?: 0} sesi aktif).")
            } else {
                appendLog("[Kontrol Musik] Izin akses notifikasi belum aktif; kontrol musik menggunakan fallback KeyEvent.")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Gagal menginisialisasi MediaSessionManager: ${e.message}")
        }
    }

    private fun cleanupMediaController() {
        try {
            activeMediaController?.unregisterCallback(mediaControllerCallback)
            activeMediaController = null
            mediaSessionManager?.removeOnActiveSessionsChangedListener(activeSessionsListener)
        } catch (_: Exception) {}
    }

    private fun updateActiveMediaController(controllers: List<MediaController>?) {
        val newController = controllers?.firstOrNull()
        if (activeMediaController?.sessionToken != newController?.sessionToken) {
            activeMediaController?.unregisterCallback(mediaControllerCallback)
            activeMediaController = newController
            activeMediaController?.registerCallback(mediaControllerCallback)
        }
        syncCurrentMediaToWatch()
    }

    fun syncCurrentMediaToWatch() {
        val controller = activeMediaController
        val metadata = controller?.metadata
        val playbackState = controller?.playbackState
        val am = audioManager ?: (getSystemService(Context.AUDIO_SERVICE) as AudioManager)

        val title = (metadata?.getString(MediaMetadata.METADATA_KEY_TITLE)
            ?: metadata?.description?.title?.toString()
            ?: "").trim()

        val artist = (metadata?.getString(MediaMetadata.METADATA_KEY_ARTIST)
            ?: metadata?.description?.subtitle?.toString()
            ?: "").trim()

        val isPlaying = playbackState?.state == PlaybackState.STATE_PLAYING
        val stateCode = when (playbackState?.state) {
            PlaybackState.STATE_PLAYING -> 1
            PlaybackState.STATE_PAUSED -> 2
            else -> 0
        }

        val maxVol = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val curVol = am.getStreamVolume(AudioManager.STREAM_MUSIC)
        val volPercent = if (maxVol > 0) ((curVol * 100) / maxVol).coerceIn(0, 100) else 50

        val durationMs = metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L
        val positionMs = playbackState?.position ?: 0L
        val durationSec = (durationMs / 1000).toInt()
        val posSec = (positionMs / 1000).toInt()

        val mediaInfo = MediaPlaybackInfo(
            title = title,
            artist = artist,
            isPlaying = isPlaying,
            volumePercent = volPercent,
            positionSeconds = posSec,
            durationSeconds = durationSec
        )
        _currentMediaState.value = mediaInfo

        val engine = protocolEngine
        if (engine != null && engine.isEncryptionInitialized) {
            val cmd = engine.buildMusicInfoCommand(
                state = stateCode,
                volumePercent = volPercent,
                track = title,
                artist = artist,
                positionSeconds = posSec,
                durationSeconds = durationSec
            )
            dispatchXiaomiProtoCommand(
                taskName = "Music Info Push",
                commandProto = cmd
            )
        }
    }

    fun handleMediaKeyFromWatch(key: Int, volume: Int) {
        val am = audioManager ?: (getSystemService(Context.AUDIO_SERVICE) as AudioManager)
        val controller = activeMediaController

        appendLog("[Kontrol Musik] Perintah dari jam tangan: key=$key, vol=$volume")

        when (key) {
            0 -> { // Play
                appendLog("[Kontrol Musik] Watch -> PLAY")
                if (controller != null) {
                    controller.transportControls.play()
                } else {
                    dispatchMediaKeyEvent(KeyEvent.KEYCODE_MEDIA_PLAY)
                }
            }
            1 -> { // Pause
                appendLog("[Kontrol Musik] Watch -> PAUSE")
                if (controller != null) {
                    controller.transportControls.pause()
                } else {
                    dispatchMediaKeyEvent(KeyEvent.KEYCODE_MEDIA_PAUSE)
                }
            }
            3 -> { // Previous
                appendLog("[Kontrol Musik] Watch -> PREVIOUS TRACK")
                if (controller != null) {
                    controller.transportControls.skipToPrevious()
                } else {
                    dispatchMediaKeyEvent(KeyEvent.KEYCODE_MEDIA_PREVIOUS)
                }
            }
            4 -> { // Next
                appendLog("[Kontrol Musik] Watch -> NEXT TRACK")
                if (controller != null) {
                    controller.transportControls.skipToNext()
                } else {
                    dispatchMediaKeyEvent(KeyEvent.KEYCODE_MEDIA_NEXT)
                }
            }
            5 -> { // Volume
                val direction = if (volume >= 50) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER
                appendLog("[Kontrol Musik] Watch -> VOLUME (${if (volume >= 50) "+1" else "-1"})")
                am.adjustStreamVolume(AudioManager.STREAM_MUSIC, direction, AudioManager.FLAG_SHOW_UI)
            }
        }

        lifecycleScope.launch(Dispatchers.Main) {
            delay(350L)
            syncCurrentMediaToWatch()
        }
    }

    private fun dispatchMediaKeyEvent(keyCode: Int) {
        val am = audioManager ?: (getSystemService(Context.AUDIO_SERVICE) as AudioManager)
        val down = KeyEvent(KeyEvent.ACTION_DOWN, keyCode)
        val up = KeyEvent(KeyEvent.ACTION_UP, keyCode)
        am.dispatchMediaKeyEvent(down)
        am.dispatchMediaKeyEvent(up)
    }

    fun sendMediaPlay() = handleMediaKeyFromWatch(0, 0)
    fun sendMediaPause() = handleMediaKeyFromWatch(1, 0)
    fun sendMediaNext() = handleMediaKeyFromWatch(4, 0)
    fun sendMediaPrevious() = handleMediaKeyFromWatch(3, 0)

    private fun closeDeadGattOnly() {
        try {
            val gatt = bluetoothGatt
            bluetoothGatt = null
            if (gatt != null) {
                refreshDeviceCache(gatt)
                gatt.close()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Exception in closeDeadGattOnly", e)
        }
    }

    private fun disconnectAndCloseGatt() {
        try {
            val gatt = bluetoothGatt
            bluetoothGatt = null
            if (gatt != null) {
                gatt.disconnect()
                gatt.close()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Exception while closing BluetoothGatt", e)
        }
    }

    private fun refreshDeviceCache(gatt: BluetoothGatt?): Boolean {
        return try {
            val refreshMethod: Method? = gatt?.javaClass?.getMethod("refresh")
            refreshMethod?.invoke(gatt) as? Boolean ?: false
        } catch (e: Exception) {
            false
        }
    }

    // =========================================================================
    // BluetoothGattCallback Implementation
    // =========================================================================

    private val gattCallback = object : BluetoothGattCallback() {

        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            cancelConnectWatchdog()
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    appendLog("Terhubung ke BLE GATT ${gatt.device.address}.")
                    reconnectAttempt = 0
                    _syncState.value = SyncState.DiscoveringServices
                    updateForegroundNotification("Terhubung! Membaca struktur GATT...")

                    mainHandler.postDelayed({
                        gatt.discoverServices()
                    }, 400)
                }

                BluetoothProfile.STATE_DISCONNECTED -> {
                    appendLog("GATT Terputus dari ${gatt.device.address} (status=$status)")
                    gattOperationCompletionSignal.trySend(false)

                    mainHandler.post {
                        try {
                            refreshDeviceCache(gatt)
                            gatt.close()
                        } catch (_: Exception) {}
                        if (bluetoothGatt === gatt) {
                            bluetoothGatt = null
                        }

                        if (!userRequestedStop) {
                            scheduleReconnect("GATT status $status")
                        } else {
                            _syncState.value = SyncState.Disconnected("Dihentikan pengguna", willReconnect = false)
                        }
                    }
                }
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            gattOperationCompletionSignal.trySend(status == BluetoothGatt.GATT_SUCCESS)
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                appendLog("Error: Pembacaan service gagal ($status)")
                scheduleReconnect("Service discovery gagal ($status)")
                return
            }

            appendLog("Layanan GATT siap (${gatt.services.size} services ditemukan).")
            handleDiscoveredGattTree(gatt)
        }

        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
            status: Int
        ) {
            gattOperationCompletionSignal.trySend(true)
        }

        @Deprecated("Deprecated in API 33")
        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                gattOperationCompletionSignal.trySend(true)
            }
        }

        override fun onDescriptorWrite(
            gatt: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int
        ) {
            val success = (status == BluetoothGatt.GATT_SUCCESS)
            gattOperationCompletionSignal.trySend(success)
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            val success = (status == BluetoothGatt.GATT_SUCCESS)
            gattOperationCompletionSignal.trySend(success)
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            handleCharacteristicNotification(characteristic.uuid, value)
        }

        @Deprecated("Deprecated in API 33, kept for API 26-32 compatibility")
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                @Suppress("DEPRECATION")
                val value = characteristic.value ?: return
                handleCharacteristicNotification(characteristic.uuid, value)
            }
        }
    }

    // =========================================================================
    // Xiaomi Wear / Longcheer GATT & SPP Handshake Orchestration
    // =========================================================================

    private fun handleDiscoveredGattTree(gatt: BluetoothGatt) {
        val allChars = mutableListOf<Pair<BluetoothGattService, BluetoothGattCharacteristic>>()
        for (service in gatt.services) {
            for (ch in service.characteristics) {
                allChars.add(service to ch)
            }
        }

        val isBonded = gatt.device.bondState == BluetoothDevice.BOND_BONDED
        appendLog("Model: Redmi Watch 3 Xiaomi Wear / Protobuf (Bonded=$isBonded).")

        // 1. Request MTU 512 (Required by Gadgetbridge XiaomiBleProtocolV1/V2 for Protobuf frames)
        enqueueGattOperation(GattOperation.RequestMtu(512))

        // 2. Subscribe to all NOTIFY / INDICATE characteristics
        val notifyChars = allChars.filter { (_, ch) ->
            (ch.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0) ||
                (ch.properties and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0)
        }

        val writeChar = allChars.firstOrNull { (_, ch) ->
            (ch.properties and BluetoothGattCharacteristic.PROPERTY_WRITE != 0) ||
                (ch.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0)
        }
        if (writeChar != null) {
            activeCommandServiceUuid = writeChar.first.uuid
            activeCommandCharUuid = writeChar.second.uuid
            appendLog("Kanal Kirim Perintah BLE (TX): ${writeChar.second.uuid.toString().substring(0, 8)}")
        }

        appendLog("Mengaktifkan notifikasi pada ${notifyChars.size} karakteristik...")
        for ((svc, ch) in notifyChars) {
            enqueueGattOperation(
                GattOperation.EnableNotification(
                    serviceUuid = svc.uuid,
                    characteristicUuid = ch.uuid,
                    useIndication = (ch.properties and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0)
                )
            )
        }

        // 3. Read readable characteristics
        val readChars = allChars.filter { (_, ch) ->
            ch.properties and BluetoothGattCharacteristic.PROPERTY_READ != 0
        }
        for ((svc, ch) in readChars) {
            enqueueGattOperation(
                GattOperation.ReadCharacteristic(
                    serviceUuid = svc.uuid,
                    characteristicUuid = ch.uuid
                )
            )
        }

        // 4. Initiate Xiaomi Wear Protobuf Auth Step 1 over BLE FDAB (and SPP if connected)
        sendXiaomiAuthStep1("BLE FDAB Init")
    }

    /**
     * Sends Xiaomi Wear Auth Step 1 (`Command(type=1, subtype=26)` containing 16-byte `phoneNonce`)
     * wrapped in both BLE SAR framing (`00000002` & `00000003`) and SPP V1/V2 framing.
     */
    private fun sendXiaomiAuthStep1(sourceLabel: String) {
        val engine = protocolEngine ?: return
        if (engine.isEncryptionInitialized) return
        // GATT discovery must not start a second handshake on an established SPP session.
        if (sppSocket?.isConnected == true && sourceLabel.startsWith("BLE")) return
        appendLog("Menginisialisasi autentikasi ($sourceLabel)...")
        _syncState.value = SyncState.Authenticating
        dispatchXiaomiProtoCommand("Auth Step 1", engine.buildAuthStep1NonceCommand(), isAuthPacket = true)
    }

    /** Exactly one transport/frame per command; never send SPP frames to BLE characteristics. */
    private fun dispatchXiaomiProtoCommand(taskName: String, commandProto: ByteArray, isAuthPacket: Boolean = false) {
        val engine = protocolEngine ?: return
        appendLog("Mengirim perintah: $taskName")
        if (sppSocket?.isConnected == true) {
            val frame = if (sppProtocolVersion >= 2) engine.encodeSppV2Command(commandProto, isAuthPacket)
                else engine.encodeSppV1Command(commandProto, isAuthPacket)
            writeSppBytes(taskName, frame)
            return
        }
        val gatt = bluetoothGatt ?: return
        val service = gatt.getService(UUID_SERVICE_FDAB) ?: return
        if (service.getCharacteristic(UUID_CHAR_FDAB_TX) != null) {
            enqueueGattOperation(GattOperation.WriteCharacteristic(
                serviceUuid = UUID_SERVICE_FDAB,
                characteristicUuid = UUID_CHAR_FDAB_TX,
                payload = engine.encodeBleSarSingleCommand(commandProto)
            ))
        }
    }

    // =========================================================================
    // Incoming Packet Parsers (SPP Stream + BLE SAR Notification)
    // =========================================================================

    private fun processIncomingSppStream(buffer: ByteArray): ByteArray {
        val engine = protocolEngine ?: return ByteArray(0)
        var offset = 0

        while (offset < buffer.size) {
            val remaining = buffer.size - offset

            // Case A: SPP V1 Frame (`BA DC FE ... EF`)
            if (remaining >= 3 &&
                buffer[offset] == 0xBA.toByte() &&
                buffer[offset + 1] == 0xDC.toByte() &&
                buffer[offset + 2] == 0xFE.toByte()
            ) {
                if (remaining < 11) break // Wait for more bytes
                val bb = ByteBuffer.wrap(buffer, offset, remaining).order(ByteOrder.LITTLE_ENDIAN)
                bb.position(offset + 3)
                val rawChannel = bb.get().toInt() and 0x0F
                val flags = bb.get().toInt() and 0xFF
                val payloadLen = (bb.short.toInt() and 0xFFFF) - 3
                if (payloadLen < 0 || remaining < payloadLen + 11) break

                val opCode = bb.get().toInt() and 0xFF
                val frameSerial = bb.get().toInt() and 0xFF
                val dataType = bb.get().toInt() and 0xFF
                val payload = ByteArray(payloadLen)
                bb.get(payload)
                val epilogue = bb.get()

                offset += (payloadLen + 11)

                if (epilogue == XiaomiWearProtocolEngine.SPP_V1_EPILOGUE) {
                    handleDecodedSppV1Packet(rawChannel, dataType, payload)
                }
                continue
            }

            // Case B: SPP V2 Frame (`A5 A5 ...`)
            if (remaining >= 2 &&
                buffer[offset] == 0xA5.toByte() &&
                buffer[offset + 1] == 0xA5.toByte()
            ) {
                if (remaining < 8) break
                val bb = ByteBuffer.wrap(buffer, offset, remaining).order(ByteOrder.LITTLE_ENDIAN)
                bb.position(offset + 2)
                val packetType = bb.get().toInt() and 0x0F
                val seqNum = bb.get().toInt() and 0xFF
                val payloadLen = bb.short.toInt() and 0xFFFF
                val checksum = bb.short.toInt() and 0xFFFF
                if (remaining < 8 + payloadLen) break

                val payload = ByteArray(payloadLen)
                bb.get(payload)
                offset += (8 + payloadLen)

                handleDecodedSppV2Packet(packetType, seqNum, payload)
                continue
            }

            offset++
        }

        return if (offset < buffer.size) buffer.copyOfRange(offset, buffer.size) else ByteArray(0)
    }

    private fun handleDecodedSppV1Packet(rawChannel: Int, dataType: Int, payload: ByteArray) {
        val engine = protocolEngine ?: return
        when (rawChannel) {
            XiaomiSppChannel.VERSION -> {
                val ver = if (payload.isNotEmpty()) payload[0].toInt() and 0xFF else 1
                appendLog("[SPP V1] Versi protokol jam diterima: v$ver (${payload.toHex()})")
                if (ver >= 2) {
                    sppProtocolVersion = 2
                    appendLog("[SPP] Beralih ke protokol SPP V2 (A5 A5)...")
                    writeSppBytes("SPP V2 Session Config", engine.buildSppV2SessionConfigRequest())
                } else {
                    sppProtocolVersion = 1
                    sendXiaomiAuthStep1("SPP V1 Version Ack")
                }
            }
            XiaomiSppChannel.PROTO_RX, XiaomiSppChannel.PROTO_TX -> {
                val plainProto = if (dataType == 1 && engine.isEncryptionInitialized) {
                    engine.decryptAesCcm(payload)
                } else {
                    payload
                }
                handleDecodedProtobufCommand(plainProto)
            }
            XiaomiSppChannel.FITNESS -> {
                if (dataType == 1 && !engine.isEncryptionInitialized) return
                val plain = if (dataType == 1) engine.decryptAesCcm(payload) else payload
                handleIncomingActivityChunk(plain)
            }
        }
    }

    private fun handleDecodedSppV2Packet(packetType: Int, seqNum: Int, payload: ByteArray) {
        val engine = protocolEngine ?: return
        sppProtocolVersion = 2
        when (packetType) {
            XiaomiWearProtocolEngine.SPP_V2_TYPE_SESSION_CONFIG -> {
                appendLog("[SPP V2] Session Config diterima (seq=$seqNum). Memulai Handshake Auth Step 1...")
                sendXiaomiAuthStep1("SPP V2 Session Ready")
            }
            XiaomiWearProtocolEngine.SPP_V2_TYPE_DATA -> {
                // Send ACK for received data packet
                val ack = engine.buildSppV2Ack(seqNum)
                if (sppSocket?.isConnected == true) {
                    writeSppBytes("SPP V2 ACK seq=$seqNum", ack)
                }
                if (payload.size < 2) return
                val rawChannel = payload[0].toInt() and 0x0F
                val opCode = payload[1].toInt() and 0xFF
                val body = payload.copyOfRange(2, payload.size)
                val plain = if (opCode == 2 && engine.isEncryptionInitialized) {
                    engine.decryptV2Ctr(body)
                } else {
                    body
                }
                if (rawChannel == 1) {
                    handleDecodedProtobufCommand(plain)
                } else if (rawChannel == 5) {
                    handleIncomingActivityChunk(plain)
                }
            }
            XiaomiWearProtocolEngine.SPP_V2_TYPE_ACK -> {
                appendLog("[SPP V2] ACK diterima dari jam (seq=$seqNum)")
            }
            XiaomiWearProtocolEngine.SPP_V2_TYPE_NACK -> {
                appendLog("[SPP V2] NACK diterima dari jam (expected seq=$seqNum)")
            }
        }
    }

    private object XiaomiSppChannel {
        const val VERSION = 0
        const val PROTO_RX = 1
        const val PROTO_TX = 2
        const val FITNESS = 3
    }

    private fun handleCharacteristicNotification(characteristicUuid: UUID, payload: ByteArray) {
        val engine = protocolEngine

        // 1. Check if payload is SPP V1 (`BA DC FE`) or SPP V2 (`A5 A5`) over BLE
        if (payload.size >= 3 &&
            payload[0] == 0xBA.toByte() &&
            payload[1] == 0xDC.toByte() &&
            payload[2] == 0xFE.toByte()
        ) {
            processIncomingSppStream(payload)
            return
        }
        if (payload.size >= 2 &&
            payload[0] == 0xA5.toByte() &&
            payload[1] == 0xA5.toByte()
        ) {
            processIncomingSppStream(payload)
            return
        }

        // 2. Check if payload is BLE V1 SAR Framing (`XiaomiCharacteristicV1`)
        if (engine != null && payload.size >= 4) {
            val bb = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN)
            val chunkIdx = bb.short.toInt() and 0xFFFF
            if (chunkIdx == 0) {
                val type = bb.get().toInt() and 0xFF
                when (type) {
                    0 -> { // Chunked Start Request from Watch
                        val encFlag = bb.get().toInt() and 0xFF
                        expectedBleChunks = if (bb.remaining() >= 2) bb.short.toInt() and 0xFFFF else 0
                        receivedBleChunks.clear()
                        appendLog("[BLE SAR] Jam meminta pengiriman $expectedBleChunks chunk (enc=$encFlag). Mengirim ACK Start...")
                        sendBleSarReply(characteristicUuid, byteArrayOf(0x00, 0x00, 0x01, 0x01))
                        return
                    }
                    2 -> { // Single Command Packet from Watch
                        sendBleSarReply(characteristicUuid, engine.buildBleSarAck())
                        val encFlag = bb.get().toInt() and 0xFF
                        val body = ByteArray(bb.remaining())
                        bb.get(body)
                        val plainProto = if (encFlag == 1 && engine.isEncryptionInitialized) {
                            engine.decryptAesCcm(body)
                        } else {
                            body
                        }
                        handleDecodedProtobufCommand(plainProto)
                        return
                    }
                    3 -> { // Command ACK from Watch
                        val res = bb.get().toInt() and 0xFF
                        appendLog("[BLE SAR] ACK diterima dari jam (status=$res)")
                        return
                    }
                }
            } else if (expectedBleChunks > 0 && chunkIdx in 1..expectedBleChunks) {
                val chunkBytes = ByteArray(bb.remaining())
                bb.get(chunkBytes)
                receivedBleChunks[chunkIdx] = chunkBytes
                appendLog("[BLE SAR] Chunk $chunkIdx/$expectedBleChunks diterima (${chunkBytes.size}b)")

                if (receivedBleChunks.size == expectedBleChunks) {
                    sendBleSarReply(characteristicUuid, byteArrayOf(0x00, 0x00, 0x01, 0x00))
                    val reconstructed = ByteArrayOutputStream()
                    for (i in 1..expectedBleChunks) {
                        receivedBleChunks[i]?.let { reconstructed.write(it) }
                    }
                    expectedBleChunks = 0
                    receivedBleChunks.clear()

                    val fullBytes = reconstructed.toByteArray()
                    val plainProto = if (engine.isEncryptionInitialized) {
                        engine.decryptAesCcm(fullBytes)
                    } else {
                        fullBytes
                    }
                    handleDecodedProtobufCommand(plainProto)
                }
                return
            }
        }

        // 3. Standard Bluetooth SIG Heart Rate Measurement (0x2A37)
        if (characteristicUuid == BleUuids.UUID_CHAR_HEART_RATE_MEASUREMENT ||
            characteristicUuid.toString().contains("2a37", ignoreCase = true)
        ) {
            handleHeartRatePayload(payload)
            return
        }

        // 4. Try direct Protobuf decode or generic telemetry fallback
        if (engine != null) {
            val decoded = engine.decodeIncomingCommandProto(payload)
            if (decoded != null) {
                handleWatchEvent(decoded)
                return
            }
        }
        parseGenericTelemetryPayload(payload)
    }

    private fun sendBleSarReply(charUuid: UUID, reply: ByteArray) {
        val gatt = bluetoothGatt ?: return
        val svc = gatt.getService(UUID_SERVICE_FDAB) ?: return
        val targetChar = if (svc.getCharacteristic(charUuid) != null) charUuid else UUID_CHAR_FDAB_TX
        enqueueGattOperation(
            GattOperation.WriteCharacteristic(
                serviceUuid = UUID_SERVICE_FDAB,
                characteristicUuid = targetChar,
                payload = reply
            )
        )
    }

    private fun handleDecodedProtobufCommand(protoBytes: ByteArray) {
        val engine = protocolEngine ?: return
        val event = engine.decodeIncomingCommandProto(protoBytes)
        if (event != null) {
            handleWatchEvent(event)
        } else {
            parseGenericTelemetryPayload(protoBytes)
        }
    }

    private fun handleWatchEvent(event: XiaomiWearProtocolEngine.DecodedWatchEvent) {
        val engine = protocolEngine ?: return
        when (event) {
            is XiaomiWearProtocolEngine.DecodedWatchEvent.WatchNonceReceived -> {
                appendLog("Handshake step 2 berhasil, memverifikasi token...")
                dispatchXiaomiProtoCommand(
                    taskName = "Auth Step 3 (AppConfirm)",
                    commandProto = event.step3ResponseCommand,
                    isAuthPacket = true
                )
            }

            is XiaomiWearProtocolEngine.DecodedWatchEvent.AuthSucceeded -> {
                appendLog("Autentikasi BERHASIL! Mode Hemat Baterai (Eco Mode) & Kontrol Musik aktif.")
                _syncState.value = SyncState.Authenticated
                reconnectAttempt = 0 // Reset reconnect backoff counter upon successful authentication
                updateForegroundNotification("Tersambung (Eco Mode) • Musik Aktif • Mengambil baterai & HR...")

                // 1. Ambil status baterai (dan cek ulang setiap 30 menit saja)
                startPeriodicBatteryMonitor()

                // 2. Ambil 1x sampel HR awal lalu otomatis matikan sensor jam (Smart Cut-Off)
                requestLiveHeartRateMeasurement()

                // 3. Sinkronisasikan status media/musik HP ke layar Redmi Watch 3
                syncCurrentMediaToWatch()
            }

            is XiaomiWearProtocolEngine.DecodedWatchEvent.RealtimeHeartRate -> {
                appendLog("Detak Jantung: ${event.bpm} BPM | Langkah: ${event.steps}")
                if (event.bpm in 30..240) {
                    saveHeartRateRecord(event.bpm)
                } else {
                    appendLog("Sedang mengukur detak jantung (HR=${event.bpm})...")
                }
            }

            is XiaomiWearProtocolEngine.DecodedWatchEvent.BatteryReport -> {
                _batteryLevel.value = event.levelPercent
                appendLog("Level Baterai: ${event.levelPercent}%")
            }

            is XiaomiWearProtocolEngine.DecodedWatchEvent.ActivityFileIdsReceived -> {
                appendLog("Menerima ${event.fileIds.size} file riwayat tidur/aktivitas dari jam.")
                for (id in event.fileIds) {
                    appendLog("File jam: ${id.toHex()} (sleep=${XiaomiSleepFileParser.isSleepFile(id)})")
                    if ((XiaomiSleepFileParser.isSleepFile(id) || XiaomiActivityFileParser.accepts(id)) && seenActivityFileIds.add(id.toHex())) {
                        if (XiaomiSleepFileParser.isSleepFile(id)) receivedSleepFile = true
                        pendingActivityFileIds.addLast(id)
                    }
                }
                if (event.subtype == XiaomiWearProtocolEngine.CMD_SUBTYPE_ACTIVITY_FETCH_TODAY) {
                    dispatchXiaomiProtoCommand(
                        "Fetch Activity Past",
                        engine.buildFetchActivityPastCommand()
                    )
                } else if (!receivedSleepFile && !requestedAlternateActivityList) {
                    requestedAlternateActivityList = true
                    appendLog("Daftar standar tidak berisi tidur; mencoba permintaan harian alternatif.")
                    dispatchXiaomiProtoCommand("Fetch Today Alternate", engine.buildFetchActivityTodayCommand(1))
                } else if (!receivedSleepFile) {
                    appendLog("Jam tidak mengirim file tidur pada kedua daftar. Data olahraga tidak digunakan sebagai tidur.")
                }
                requestNextActivityFile()
            }

            is XiaomiWearProtocolEngine.DecodedWatchEvent.NotificationDismissed -> {
                appendLog("[Notifikasi] Notifikasi #${event.id} ditutup oleh pengguna di smartwatch.")
            }

            is XiaomiWearProtocolEngine.DecodedWatchEvent.CallRejectReceived -> {
                appendLog("[Telepon] Panggilan ditolak melalui smartwatch.")
            }

            is XiaomiWearProtocolEngine.DecodedWatchEvent.CallIgnoreReceived -> {
                appendLog("[Telepon] Panggilan diabaikan/disenyapkan melalui smartwatch.")
            }

            is XiaomiWearProtocolEngine.DecodedWatchEvent.MediaKeyReceived -> {
                handleMediaKeyFromWatch(event.key, event.volume)
            }

            is XiaomiWearProtocolEngine.DecodedWatchEvent.RawCommand -> {
                // Ignore raw background internal pings
            }
        }
    }

    @Synchronized
    private fun requestNextActivityFile() {
        if (activeActivityFileId != null) return
        val engine = protocolEngine ?: return
        val nextId = pendingActivityFileIds.removeFirstOrNull()
        if (nextId == null) {
            appendLog("Antrean file aktivitas selesai. Menunggu daftar/permintaan berikutnya.")
            if (_syncState.value is SyncState.SyncingSleep) _syncState.value = SyncState.Authenticated
            return
        }
        activeActivityFileId = nextId
        activityTransfer.begin(nextId)
        appendLog("Meminta unduhan file aktivitas ID: ${nextId.toHex()}...")
        activityHandler.removeCallbacks(activityTimeout)
        activityHandler.postDelayed(activityTimeout, 15_000L)
        dispatchXiaomiProtoCommand("Request Activity File", engine.buildRequestActivityFileCommand(nextId))
    }

    @Synchronized
    private fun handleIncomingActivityChunk(chunk: ByteArray) {
        val data = try {
            activityTransfer.accept(chunk)
        } catch (e: IllegalArgumentException) {
            val diagnostic = chunk.copyOf(minOf(chunk.size, 8192))
            lifecycleScope.launch(ioDispatcher) {
                runCatching { java.io.File(cacheDir, "last_rejected_activity.bin").writeBytes(diagnostic) }
            }
            appendLog("Transfer tidur ditolak: ${e.message}")
            activityHandler.removeCallbacks(activityTimeout)
            activeActivityFileId = null
            activityTransfer.reset()
            _syncState.value = SyncState.Error("Transfer tidur tidak valid; coba sinkronkan ulang")
            requestNextActivityFile()
            return
        }
        activityHandler.removeCallbacks(activityTimeout)
        if (data == null) {
            activityHandler.postDelayed(activityTimeout, 15_000L)
            _syncState.value = SyncState.SyncingSleep(activityTransfer.bytesBuffered)
            return
        }
        activeActivityFileId = null
        lifecycleScope.launch(ioDispatcher) {
            try {
                java.io.File(cacheDir, "last_activity_file.bin").writeBytes(data)
                val crc = java.util.zip.CRC32().apply { update(data, 0, data.size - 4) }.value
                val expected = ByteBuffer.wrap(data, data.size - 4, 4).order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xffffffffL
                require(crc == expected) { "Checksum aktivitas tidak sesuai" }
                val archive = java.io.File(cacheDir, "activity_diagnostics").apply { mkdirs() }
                java.io.File(archive, data.copyOfRange(0, 7).toHex().replace(" ", "") + ".bin").writeBytes(data)
                archive.listFiles()?.sortedByDescending { it.lastModified() }?.drop(32)?.forEach { it.delete() }
                appendLog("File diterima: ${data.copyOfRange(0, 7).toHex()}, ${data.size} bytes, CRC valid")
                if (!XiaomiSleepFileParser.isSleepFile(data.copyOfRange(0, 7))) {
                    if (!XiaomiActivityFileParser.accepts(data.copyOfRange(0, 7))) {
                        appendLog("Jenis file aktivitas belum didukung; file diagnostik disimpan tanpa membuat metrik.")
                        return@launch
                    }
                    val records = XiaomiActivityFileParser.parse(data)
                    db.activityRecordDao().insert(records)
                    val manualHrs = records.filter { it.category == "heart_rate" && it.averageHeartRate != null }
                    if (manualHrs.isNotEmpty()) {
                        db.heartRateDao().insertAll(
                            manualHrs.map {
                                HeartRateEntity(
                                    timestamp = it.timestamp,
                                    bpm = it.averageHeartRate!!,
                                    syncedToWebhook = false
                                )
                            }
                        )
                    }
                    appendLog("Aktivitas tersimpan: ${records.size} rekaman (${records.firstOrNull()?.category ?: "kosong"}).")
                    return@launch
                }
                val parsed = XiaomiSleepFileParser.parse(data)
                db.sleepStageDao().saveVerifiedSleep(parsed.session, parsed.stages)
                java.io.File(cacheDir, "last_sleep_file.bin").writeBytes(data)
                val session = parsed.session
                appendLog("Tidur terverifikasi: ${session.totalMinutes} menit, ${session.startTimestamp} - ${session.endTimestamp}, HR=${session.averageHeartRate ?: "-"}, ${parsed.stages.size} fase.")
                _syncState.value = SyncState.SleepSyncCompleted(parsed.stages.size)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                appendLog("File aktivitas ditolak: ${e.message}")
                _syncState.value = SyncState.Error("File aktivitas belum dapat dibaca: ${e.message}")
            } finally {
                requestNextActivityFile()
            }
        }
    }

    // =========================================================================
    // Eco Power Management: Smart Burst HR Cut-Off, On-Demand Stream & 30m Battery
    // =========================================================================

    private var hrPollingJob: Job? = null
    private var batteryMonitorJob: Job? = null
    private var lastHrSavedTimestamp: Long = 0L
    @Volatile
    private var isWatchHrSensorActive: Boolean = false

    private val hrSensorCutOffRunnable = Runnable {
        stopWatchHrSensorHardware(reason = "Batas waktu baca 8 dtk tercapai (Eco Cut-Off)")
    }

    fun queryBatteryStatus() {
        val engine = protocolEngine ?: return
        if (!engine.isEncryptionInitialized) return
        dispatchXiaomiProtoCommand("Query Battery (type=2, subtype=1)", engine.buildBatteryQueryCommand())
    }

    /**
     * Checks battery once immediately on connect, then only every 30 minutes (instead of 60s)
     * so the watch radio can remain in low-power Sniff Mode.
     */
    private fun startPeriodicBatteryMonitor() {
        batteryMonitorJob?.cancel()
        batteryMonitorJob = lifecycleScope.launch(ioDispatcher) {
            while (isActive && _syncState.value !is SyncState.Disconnected) {
                if (protocolEngine?.isEncryptionInitialized == true) {
                    queryBatteryStatus()
                }
                delay(BATTERY_POLL_INTERVAL_MS)
            }
        }
    }

    /**
     * Toggles On-Demand Eco Heart Rate Streaming on or off.
     */
    fun toggleHeartRateStream(): Boolean {
        return if (_isHrStreaming.value) {
            stopHeartRateStream()
            false
        } else {
            startHeartRateStream()
            true
        }
    }

    /**
     * Starts periodic 15-second Eco Burst HR polling.
     * In each 15-second cycle, the sensor is turned ON (`subtype=45`) and immediately turned OFF (`subtype=46`)
     * as soon as 1 valid BPM reading arrives!
     */
    fun startHeartRateStream() {
        val engine = protocolEngine
        if (engine == null || !engine.isEncryptionInitialized || sppSocket?.isConnected != true) {
            appendLog("Koneksi serial belum siap. Tunggu autentikasi atau tekan Hubungkan terlebih dahulu.")
            return
        }

        _isHrStreaming.value = true
        appendLog("Eco HR Stream DIAKTIFKAN (Burst 15s + Auto Sensor Cut-Off).")
        hrPollingJob?.cancel()
        hrPollingJob = lifecycleScope.launch(ioDispatcher) {
            while (isActive && _isHrStreaming.value && _syncState.value !is SyncState.Disconnected) {
                if (protocolEngine?.isEncryptionInitialized == true) {
                    requestLiveHeartRateMeasurement()
                }
                delay(HR_POLL_INTERVAL_MS)
            }
        }
    }

    /**
     * Stops periodic HR polling and immediately sends `CMD_SUBTYPE_REALTIME_STATS_STOP (subtype=46)`
     * so the watch turns off its green optical PPG sensor completely.
     */
    fun stopHeartRateStream(silent: Boolean = false) {
        _isHrStreaming.value = false
        hrPollingJob?.cancel()
        hrPollingJob = null
        stopWatchHrSensorHardware(
            reason = if (silent) "Layanan berhenti" else "Dihentikan oleh pengguna",
            forceSend = true
        )
        if (!silent) {
            appendLog("Eco HR Stream DIHENTIKAN. Sensor optik jam dimatikan untuk menghemat baterai.")
            if (protocolEngine?.isEncryptionInitialized == true) {
                _syncState.value = SyncState.Authenticated
                updateForegroundNotification("Redmi Watch 3 • Tersambung (Mode Hemat Daya)")
            }
        }
    }

    /**
     * Sends `Command(type=8, subtype=46)` (`CMD_SUBTYPE_REALTIME_STATS_STOP`) to turn off the watch's
     * optical PPG sensor immediately after a reading is obtained or when streaming is stopped.
     */
    private fun stopWatchHrSensorHardware(reason: String, forceSend: Boolean = false) {
        mainHandler.removeCallbacks(hrSensorCutOffRunnable)
        val engine = protocolEngine ?: return
        if (!isWatchHrSensorActive && !forceSend) return
        isWatchHrSensorActive = false

        if (engine.isEncryptionInitialized && (sppSocket?.isConnected == true || bluetoothGatt != null)) {
            val stopRealtimeCmd = engine.buildRealtimeStatsCommand(enable = false)
            dispatchXiaomiProtoCommand("Stop Realtime HR Sensor (type=8, subtype=46) [$reason]", stopRealtimeCmd)
        }
    }

    /**
     * Triggers a single burst of Redmi Watch 3's Real-Time Stats (`Command(type=8, subtype=45)`),
     * protected by an 8-second auto-cutoff timer and immediate cutoff upon receiving a valid BPM.
     */
    fun requestLiveHeartRateMeasurement() {
        val engine = protocolEngine
        if (engine == null || !engine.isEncryptionInitialized || sppSocket?.isConnected != true) {
            appendLog("Koneksi serial belum siap. Tunggu autentikasi atau tekan Hubungkan terlebih dahulu.")
            return
        }

        isWatchHrSensorActive = true
        mainHandler.removeCallbacks(hrSensorCutOffRunnable)
        mainHandler.postDelayed(hrSensorCutOffRunnable, HR_SENSOR_CUTOFF_TIMEOUT_MS)

        val startRealtimeCmd = engine.buildRealtimeStatsCommand(enable = true)
        dispatchXiaomiProtoCommand("Start Burst Heart Rate (type=8, subtype=45)", startRealtimeCmd)
    }

    private fun handleHeartRatePayload(payload: ByteArray) {
        if (payload.size < 2) return
        val flags = payload[0].toInt()
        val isUint16Format = (flags and 0x01) != 0
        val bpm = if (isUint16Format && payload.size >= 3) {
            ((payload[2].toInt() and 0xFF) shl 8) or (payload[1].toInt() and 0xFF)
        } else {
            payload[1].toInt() and 0xFF
        }
        saveHeartRateRecord(bpm)
    }

    private fun parseGenericTelemetryPayload(payload: ByteArray) {
        if (payload.isEmpty()) return
        if (payload.size >= 2 && (payload[0] == 0x15.toByte() || payload[0] == 0x02.toByte())) {
            val bpm = payload[1].toInt() and 0xFF
            if (bpm in 40..220) {
                saveHeartRateRecord(bpm)
                return
            }
        }
    }

    private fun saveHeartRateRecord(bpm: Int) {
        if (bpm !in 30..240) return
        val recordTimestamp = System.currentTimeMillis()

        // 1. Immediately turn off watch PPG optical sensor after capturing valid BPM (Smart Burst Cut-Off)
        stopWatchHrSensorHardware(reason = "Sampel $bpm BPM diterima")

        // 2. Always update live state for instant display
        _heartRateBpm.value = bpm
        if (_isHrStreaming.value) {
            _syncState.value = SyncState.StreamingHeartRate(bpm)
            updateForegroundNotification("Redmi Watch 3 • Eco HR: $bpm BPM")
        } else {
            updateForegroundNotification("Redmi Watch 3 • Tersambung (HR Terakhir: $bpm BPM)")
        }

        // 3. 15 seconds throttle for SQLite insert & terminal log
        if (recordTimestamp - lastHrSavedTimestamp >= 14_000L) {
            lastHrSavedTimestamp = recordTimestamp
            appendLog("Detak Jantung: $bpm BPM (Sensor jam otomatis diistirahatkan)")

            lifecycleScope.launch(ioDispatcher) {
                try {
                    db.heartRateDao().insert(
                        HeartRateEntity(
                            timestamp = recordTimestamp,
                            bpm = bpm,
                            syncedToWebhook = false
                        )
                    )
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to insert HeartRateEntity into Room DB", e)
                }
            }
        }
    }

    fun triggerSleepDataSync(sinceEpochMillis: Long) {
        val engine = protocolEngine
        if (engine == null || !engine.isEncryptionInitialized || sppSocket?.isConnected != true) {
            appendLog("Koneksi serial belum siap. Tunggu autentikasi atau tekan Connect terlebih dahulu.")
            return
        }

        if (activeActivityFileId != null) {
            appendLog("Unduhan aktivitas masih berjalan.")
            return
        }
        activityTransfer.reset()
        pendingActivityFileIds.clear()
        seenActivityFileIds.clear()
        requestedAlternateActivityList = false
        receivedSleepFile = false
        appendLog("Meminta tidur, ringkasan harian, pengukuran manual, dan riwayat olahraga dari jam.")
        _syncState.value = SyncState.SyncingSleep(bytesBuffered = 0)
        updateForegroundNotification("Sinkronisasi data tidur & aktivitas...")

        val fetchTodayCmd = engine.buildFetchActivityTodayCommand()
        dispatchXiaomiProtoCommand("Fetch Recorded Sleep/Activity Today (type=8, subtype=1)", fetchTodayCmd)
    }

    fun triggerWebhookExport(webhookUrl: String) {
        val mac = targetMacAddress ?: "UNKNOWN_MAC"
        lifecycleScope.launch(ioDispatcher) {
            appendLog("Mengirim data ke Webhook: $webhookUrl...")
            when (val res = webhookDispatcher.dispatchPendingRecords(webhookUrl, mac)) {
                is WebhookDispatcher.DispatchResult.Success -> {
                    appendLog("Webhook Sukses: ${res.hrExported} HR, ${res.sleepExported} Sleep, ${res.activityExported} aktivitas terkirim.")
                    _syncState.value = SyncState.WebhookDispatched(res.hrExported, res.sleepExported, res.activityExported)
                }
                is WebhookDispatcher.DispatchResult.NoPendingData -> {
                    appendLog("Webhook: Belum ada rekaman baru di SQLite (lakukan pengukuran detak jantung atau sync tidur terlebih dahulu).")
                }
                is WebhookDispatcher.DispatchResult.Error -> {
                    appendLog("Webhook Gagal: ${res.message}")
                    _syncState.value = SyncState.Error("Webhook error: ${res.message}")
                }
            }
        }
    }

    // =========================================================================
    // Smart Notification & Call Forwarding to Redmi Watch 3
    // =========================================================================

    /**
     * Forwards an intercepted phone notification (WhatsApp, Telegram, SMS, incoming call)
     * to Redmi Watch 3 via the encrypted Xiaomi Wear protobuf channel (`CMD_TYPE_NOTIFICATION = 7`).
     */
    fun forwardNotification(
        id: Int,
        packageName: String,
        appName: String,
        title: String,
        body: String,
        timestampMillis: Long = System.currentTimeMillis(),
        isCall: Boolean = false,
        key: String? = null
    ) {
        val engine = protocolEngine
        if (engine == null || !engine.isEncryptionInitialized) {
            appendLog("[Notifikasi] Jam belum terhubung atau belum terautentikasi (notifikasi dilewati).")
            return
        }

        val date = Date(timestampMillis)
        val cmdProto = engine.buildNotificationSendCommand(
            id = id,
            packageName = packageName,
            appName = appName,
            title = title,
            body = body,
            timestamp = date,
            isCall = isCall,
            key = key
        )

        appendLog("[Notifikasi] Teruskan ke jam: $appName | $title: ${body.take(30)}...")
        dispatchXiaomiProtoCommand("Notifikasi ($appName)", cmdProto)
    }

    /**
     * Sends Notification Dismiss Command to remove the alert from Redmi Watch 3.
     */
    fun dismissNotificationOnWatch(
        id: Int,
        packageName: String = "",
        key: String? = null
    ) {
        val engine = protocolEngine
        if (engine == null || !engine.isEncryptionInitialized) return

        val cmdProto = engine.buildNotificationDismissCommand(id, packageName, key)
        dispatchXiaomiProtoCommand("Dismiss Notifikasi ($id)", cmdProto)
    }

    // =========================================================================
    // Serialized GATT Operation Queue
    // =========================================================================

    private fun enqueueGattOperation(operation: GattOperation) {
        gattOperationQueue.trySend(operation)
    }

    private fun startGattOperationProcessor() {
        lifecycleScope.launch(ioDispatcher) {
            for (op in gattOperationQueue) {
                val gatt = bluetoothGatt ?: continue
                val initiated = when (op) {
                    is GattOperation.RequestMtu -> gatt.requestMtu(op.mtu)
                    is GattOperation.EnableNotification -> executeEnableNotification(gatt, op)
                    is GattOperation.WriteCharacteristic -> executeWriteCharacteristic(gatt, op)
                    is GattOperation.ReadCharacteristic -> executeReadCharacteristic(gatt, op)
                }
                if (initiated) {
                    gattOperationCompletionSignal.receive()
                }
            }
        }
    }

    private fun executeReadCharacteristic(
        gatt: BluetoothGatt,
        op: GattOperation.ReadCharacteristic
    ): Boolean {
        val service = gatt.getService(op.serviceUuid) ?: return false
        val characteristic = service.getCharacteristic(op.characteristicUuid) ?: return false
        return gatt.readCharacteristic(characteristic)
    }

    private fun executeEnableNotification(
        gatt: BluetoothGatt,
        op: GattOperation.EnableNotification
    ): Boolean {
        val service = gatt.getService(op.serviceUuid) ?: return false
        val characteristic = service.getCharacteristic(op.characteristicUuid) ?: return false

        val localSet = gatt.setCharacteristicNotification(characteristic, true)
        if (!localSet) return false

        val cccdDescriptor = characteristic.getDescriptor(BleUuids.UUID_DESCRIPTOR_CCCD) ?: return false

        val descriptorValue = if (op.useIndication) {
            BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
        } else {
            BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        }

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            gatt.writeDescriptor(cccdDescriptor, descriptorValue) == BluetoothGatt.GATT_SUCCESS
        } else {
            @Suppress("DEPRECATION")
            cccdDescriptor.value = descriptorValue
            @Suppress("DEPRECATION")
            gatt.writeDescriptor(cccdDescriptor)
        }
    }

    private fun executeWriteCharacteristic(
        gatt: BluetoothGatt,
        op: GattOperation.WriteCharacteristic
    ): Boolean {
        val service = gatt.getService(op.serviceUuid) ?: return false
        val characteristic = service.getCharacteristic(op.characteristicUuid) ?: return false

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            gatt.writeCharacteristic(characteristic, op.payload, op.writeType) == BluetoothGatt.GATT_SUCCESS
        } else {
            @Suppress("DEPRECATION")
            characteristic.writeType = op.writeType
            @Suppress("DEPRECATION")
            characteristic.value = op.payload
            @Suppress("DEPRECATION")
            gatt.writeCharacteristic(characteristic)
        }
    }

    // =========================================================================
    // Foreground Service Notification Management
    // =========================================================================

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            NOTIFICATION_CHANNEL_ID,
            "RW3 DirectSync BLE Connection",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Menjaga koneksi langsung BLE & SPP ke Redmi Watch 3 tetap aktif"
            setShowBadge(false)
        }
        val manager = getSystemService(NotificationManager::class.java)
        manager?.createNotificationChannel(channel)
    }

    private fun buildNotification(contentText: String): Notification {
        val openAppIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("RW3-DirectSync")
            .setContentText(contentText)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(pendingIntent)
            .build()
    }

    private fun promoteToForeground(statusText: String) {
        val notification = buildNotification(statusText)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun updateForegroundNotification(statusText: String) {
        val manager = getSystemService(NotificationManager::class.java)
        manager?.notify(NOTIFICATION_ID, buildNotification(statusText))
    }

    private fun ByteArray.toHex(): String =
        joinToString(separator = " ") { byte -> "%02X".format(byte) }
}
