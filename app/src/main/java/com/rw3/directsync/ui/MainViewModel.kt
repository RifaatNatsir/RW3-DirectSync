package com.rw3.directsync.ui

import android.app.Application
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.rw3.directsync.ble.BleSyncService
import com.rw3.directsync.crypto.XiaomiAuthCrypto
import com.rw3.directsync.data.db.Rw3Database
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.rw3.directsync.data.SleepHistoryCache
import com.rw3.directsync.data.ActivityHistoryCache
import com.rw3.directsync.data.DailyActivityReducer
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.Dispatchers

/**
 * MVVM ViewModel managing credentials, Webhook URL, Room database metrics observation,
 * discovered BLE devices, and live diagnostic logs.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class MainViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        private const val PREFS_NAME = "rw3_directsync_prefs"
        private const val KEY_MAC_ADDRESS = "mac_address"
        private const val KEY_AUTH_KEY = "auth_key"
        private const val KEY_WEBHOOK_URL = "webhook_url"
        private const val DEFAULT_MAC_ADDRESS = "82:77:16:9E:07:01"
        private const val DEFAULT_AUTH_KEY = "02b39353986a04abbc3509ecabc235e7"
        private const val DEFAULT_WEBHOOK_URL = "http://192.168.1.100:8080/api/rw3/metrics"
    }

    private val prefs = application.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val db = Rw3Database.getDatabase(application)

    init { viewModelScope.launch { SleepHistoryCache.recover(application) } }
    init { viewModelScope.launch { ActivityHistoryCache.recover(application) } }

    private val _macAddress = MutableStateFlow(
        prefs.getString(KEY_MAC_ADDRESS, DEFAULT_MAC_ADDRESS) ?: DEFAULT_MAC_ADDRESS
    )
    val macAddress: StateFlow<String> = _macAddress.asStateFlow()

    private val _authKey = MutableStateFlow(
        prefs.getString(KEY_AUTH_KEY, DEFAULT_AUTH_KEY) ?: DEFAULT_AUTH_KEY
    )
    val authKey: StateFlow<String> = _authKey.asStateFlow()

    private val _webhookUrl = MutableStateFlow(
        prefs.getString(KEY_WEBHOOK_URL, DEFAULT_WEBHOOK_URL) ?: DEFAULT_WEBHOOK_URL
    )
    val webhookUrl: StateFlow<String> = _webhookUrl.asStateFlow()

    private val _syncState = MutableStateFlow<BleSyncService.SyncState>(BleSyncService.SyncState.Idle)
    val syncState: StateFlow<BleSyncService.SyncState> = _syncState.asStateFlow()

    private val _batteryLevel = MutableStateFlow<Int?>(null)
    val batteryLevel: StateFlow<Int?> = _batteryLevel.asStateFlow()

    private val _liveBpm = MutableStateFlow<Int?>(null)
    val liveBpm: StateFlow<Int?> = _liveBpm.asStateFlow()

    private val _isHrStreaming = MutableStateFlow(false)
    val isHrStreaming: StateFlow<Boolean> = _isHrStreaming.asStateFlow()

    private val _discoveredDevices = MutableStateFlow<List<BleSyncService.DiscoveredBleDevice>>(emptyList())
    val discoveredDevices: StateFlow<List<BleSyncService.DiscoveredBleDevice>> = _discoveredDevices.asStateFlow()

    private val _diagnosticLogs = MutableStateFlow<List<String>>(emptyList())
    val diagnosticLogs: StateFlow<List<String>> = _diagnosticLogs.asStateFlow()

    val dailyActivity = combine(db.activityRecordDao().observeDailySnapshots(), db.dailyActivityMinuteDao().observeRecent()) {
            snapshots, minutes -> DailyActivityReducer.merge(snapshots, minutes)
        }.flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.Lazily, null)
    val workouts = db.activityRecordDao().observeWorkouts()
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())
    val latestSpo2 = db.activityRecordDao().observeMeasurement("spo2")
        .stateIn(viewModelScope, SharingStarted.Lazily, null)
    val latestStress = db.activityRecordDao().observeMeasurement("stress")
        .stateIn(viewModelScope, SharingStarted.Lazily, null)
    val latestManualHr = db.activityRecordDao().observeMeasurement("heart_rate")
        .stateIn(viewModelScope, SharingStarted.Lazily, null)

    val totalHeartRateCount: StateFlow<Int> = db.heartRateDao()
        .observeTotalCount()
        .stateIn(viewModelScope, SharingStarted.Lazily, 0)

    data class HeartRate24hStats(
        val minBpm: Int? = null,
        val maxBpm: Int? = null,
        val avgBpm: Int? = null,
        val count: Int = 0
    )

    val heartRateWindowHours = MutableStateFlow(24)
    private val clock = flow {
        while (true) { emit(System.currentTimeMillis()); delay(60_000) }
    }
    val latestHeartRate = db.heartRateDao().observeRecentRecords(1)
        .map { it.firstOrNull() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val heartRate24hRecords: StateFlow<List<com.rw3.directsync.data.db.entity.HeartRateEntity>> =
        combine(heartRateWindowHours, clock) { hours, now -> now - hours * 3_600_000L }
        .flatMapLatest { cutoff -> db.heartRateDao().observeRecordsSince(cutoff) }
        .map { list -> list.filter { it.timestamp <= System.currentTimeMillis() }.distinctBy { it.timestamp } }
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    val heartRate24hStats: StateFlow<HeartRate24hStats> = heartRate24hRecords
        .map { records ->
            if (records.isEmpty()) {
                HeartRate24hStats()
            } else {
                val validBpm = records.map { it.bpm }.filter { it in 30..240 }
                if (validBpm.isEmpty()) {
                    HeartRate24hStats()
                } else {
                    HeartRate24hStats(
                        minBpm = validBpm.minOrNull(),
                        maxBpm = validBpm.maxOrNull(),
                        avgBpm = validBpm.average().toInt(),
                        count = validBpm.size
                    )
                }
            }
        }
        .stateIn(viewModelScope, SharingStarted.Lazily, HeartRate24hStats())

    val totalSleepStageCount: StateFlow<Int> = db.sleepStageDao()
        .observeTotalCount()
        .stateIn(viewModelScope, SharingStarted.Lazily, 0)

    val storedRecordsCount: StateFlow<Int> = combine(
        totalHeartRateCount,
        totalSleepStageCount,
        db.activityRecordDao().observeCount()
    ) { hr, sleep, activity -> hr + sleep + activity }
        .stateIn(viewModelScope, SharingStarted.Lazily, 0)

    val unsyncedRecordsCount: StateFlow<Int> = combine(
        db.heartRateDao().observeUnsyncedCount(),
        db.sleepStageDao().observeUnsyncedCount(),
        db.activityRecordDao().observeUnsyncedCount()
    ) { hr, sleep, activity -> hr + sleep + activity }
        .stateIn(viewModelScope, SharingStarted.Lazily, 0)

    data class SleepSummary(
        val totalMinutes: Int = 0,
        val deepMinutes: Int = 0,
        val lightMinutes: Int = 0,
        val remMinutes: Int = 0,
        val awakeMinutes: Int = 0,
        val deepRatioPercent: Int = 0,
        val sleepScore: Int? = null,
        val hasData: Boolean = false,
        val startTimestamp: Long = 0,
        val endTimestamp: Long = 0,
        val averageHeartRate: Int? = null,
        val hasStageData: Boolean = false,
        val sourceFileId: String = ""
    )

    val sleepHistory = db.sleepStageDao().observeSessions()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val sleepSummary: StateFlow<SleepSummary> = db.sleepStageDao()
        .observeLatestSession()
        .map { session ->
            if (session == null) SleepSummary() else SleepSummary(
                totalMinutes = session.totalMinutes,
                deepMinutes = session.deepMinutes,
                lightMinutes = session.lightMinutes,
                remMinutes = session.remMinutes,
                awakeMinutes = session.awakeMinutes,
                deepRatioPercent = if (session.totalMinutes > 0) session.deepMinutes * 100 / session.totalMinutes else 0,
                sleepScore = session.sleepScore,
                hasData = true,
                startTimestamp = session.startTimestamp,
                endTimestamp = session.endTimestamp,
                averageHeartRate = session.averageHeartRate,
                hasStageData = session.hasStageData,
                sourceFileId = session.sourceFileId
            )
        }
        .stateIn(viewModelScope, SharingStarted.Lazily, SleepSummary())

    fun saveCredentialsAndWebhook(
        mac: String,
        key: String,
        webhook: String
    ): Result<Triple<String, String, String>> {
        val cleanMac = mac.trim().uppercase()
        val cleanKey = key.trim().removePrefix("0x").removePrefix("0X")
        val cleanWebhook = webhook.trim()

        if (cleanWebhook.isNotEmpty() && cleanWebhook.toHttpUrlOrNull() == null) {
            return Result.failure(IllegalArgumentException("URL Webhook tidak valid (gunakan http:// atau https://)"))
        }

        val macRegex = Regex("^([0-9A-F]{2}:){5}[0-9A-F]{2}$")
        if (!macRegex.matches(cleanMac)) {
            return Result.failure(IllegalArgumentException("Format MAC Address tidak valid (contoh: AA:BB:CC:11:22:33)"))
        }

        return try {
            XiaomiAuthCrypto.parseAuthKeyHex(cleanKey)
            prefs.edit()
                .putString(KEY_MAC_ADDRESS, cleanMac)
                .putString(KEY_AUTH_KEY, cleanKey)
                .putString(KEY_WEBHOOK_URL, cleanWebhook)
                .apply()
            _macAddress.value = cleanMac
            _authKey.value = cleanKey
            _webhookUrl.value = cleanWebhook
            Result.success(Triple(cleanMac, cleanKey, cleanWebhook))
        } catch (e: IllegalArgumentException) {
            Result.failure(e)
        }
    }

    fun saveWebhookUrl(url: String): Boolean {
        val clean = url.trim()
        if (clean.toHttpUrlOrNull() == null) return false
        prefs.edit().putString(KEY_WEBHOOK_URL, clean).apply()
        _webhookUrl.value = clean
        return true
    }

    fun onBatteryLevelChanged(level: Int?) {
        _batteryLevel.value = level
    }

    fun onHeartRateBpmChanged(bpm: Int?) {
        _liveBpm.value = bpm
    }

    fun onHrStreamingChanged(streaming: Boolean) {
        _isHrStreaming.value = streaming
    }

    fun onServiceStateChanged(state: BleSyncService.SyncState) {
        _syncState.value = state
        if (state is BleSyncService.SyncState.StreamingHeartRate) {
            _liveBpm.value = state.latestBpm
        }
    }

    fun onDiscoveredDevicesChanged(devices: List<BleSyncService.DiscoveredBleDevice>) {
        _discoveredDevices.value = devices
    }

    fun onDiagnosticLogsChanged(logs: List<String>) {
        _diagnosticLogs.value = logs
    }

    fun clearDiagnosticLogs() {
        _diagnosticLogs.value = emptyList()
    }
}
