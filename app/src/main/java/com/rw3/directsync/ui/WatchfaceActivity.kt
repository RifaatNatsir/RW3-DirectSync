package com.rw3.directsync.ui

import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.widget.Toast
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.rw3.directsync.R
import com.rw3.directsync.ble.BleSyncService
import com.rw3.directsync.ble.protocol.XiaomiWearProtocolEngine
import com.rw3.directsync.databinding.ActivityWatchfaceBinding
import com.rw3.directsync.watchface.WallpaperBinPackager
import com.rw3.directsync.watchface.WallpaperImageProcessor
import com.rw3.directsync.watchface.WatchfaceAdapter
import com.rw3.directsync.watchface.WatchfacePackageValidator
import com.rw3.directsync.watchface.WatchfaceTransferState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class WatchfaceActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "WatchfaceActivity"
    }

    private lateinit var binding: ActivityWatchfaceBinding
    private var bleService: BleSyncService? = null
    private var isServiceBound = false
    private var serviceObserveJob: Job? = null

    private lateinit var watchfaceAdapter: WatchfaceAdapter

    // Selected photo state
    private var selectedRawBitmap: Bitmap? = null
    private var currentZoomScale: Float = 1.0f
    private var currentPanX: Float = 0.5f
    private var currentPanY: Float = 0.5f
    private var processedWallpaperBitmap: Bitmap? = null
    private var processedPreviewBitmap: Bitmap? = null
    private var lastTouchX: Float = 0f
    private var lastTouchY: Float = 0f

    // Imported package state
    private var importedPackageData: ByteArray? = null
    private var importedValidationResult: WatchfacePackageValidator.ValidationResult? = null
    private var lastAttemptedWatchfaceId: String? = null
    private var lastAttemptedUploadBytes: ByteArray? = null

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val localBinder = binder as? BleSyncService.LocalBinder ?: return
            val service = localBinder.getService()
            bleService = service
            service.autoConnectFromSavedPrefsIfIdle()
            observeServiceState(service)
            // Query installed watchfaces only if not yet loaded
            if (service.installedWatchfaces.value.isEmpty()) {
                service.queryWatchfaceList()
            }
            handleDebugIntentIfPresent(intent)
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            bleService = null
            updateConnectionUi(false)
        }
    }

    // Primary Image Picker using GetContent("image/*") - fast, lightweight, avoids PhotoPicker OOM on XOS/OEM ROMs
    private val pickImageGetContentLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            loadAndProcessPhoto(uri)
        }
    }

    // Fallback Photo Picker launcher (PickVisualMedia)
    private val pickPhotoLauncher = registerForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri: Uri? ->
        if (uri != null) {
            loadAndProcessPhoto(uri)
        }
    }

    // Custom Watchface Package Document Picker
    private val importPackageLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            loadAndValidatePackage(uri)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityWatchfaceBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupToolbar()
        setupRecyclerView()
        setupClickListeners()
        setupPreviewTouchPan()

        val intent = Intent(this, BleSyncService::class.java)
        isServiceBound = bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceObserveJob?.cancel()
        if (isServiceBound) {
            try {
                unbindService(serviceConnection)
            } catch (_: Exception) {}
            isServiceBound = false
        }
        bleService = null
        selectedRawBitmap = null
        processedWallpaperBitmap = null
    }

    private fun setupToolbar() {
        binding.btnBack.setOnClickListener {
            finish()
        }
        binding.btnRefreshWatchfacesTop.setOnClickListener {
            bleService?.queryWatchfaceList()
            Toast.makeText(this, "Meminta daftar watchface dari jam...", Toast.LENGTH_SHORT).show()
        }
    }

    private fun setupRecyclerView() {
        watchfaceAdapter = WatchfaceAdapter(
            onApplyClick = { item ->
                showApplyConfirmDialog(item)
            },
            onDeleteClick = { item ->
                showDeleteConfirmDialog(item)
            }
        )
        binding.rvInstalledWatchfaces.layoutManager = LinearLayoutManager(this)
        binding.rvInstalledWatchfaces.adapter = watchfaceAdapter
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupPreviewTouchPan() {
        binding.imgWatchPreview.setOnTouchListener { view, event ->
            if (selectedRawBitmap == null) return@setOnTouchListener false
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    view.parent?.requestDisallowInterceptTouchEvent(true)
                    lastTouchX = event.x
                    lastTouchY = event.y
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.x - lastTouchX
                    val dy = event.y - lastTouchY
                    lastTouchX = event.x
                    lastTouchY = event.y
                    val viewW = view.width.coerceAtLeast(1).toFloat()
                    val viewH = view.height.coerceAtLeast(1).toFloat()
                    currentPanX = (currentPanX - (dx / viewW)).coerceIn(0f, 1f)
                    currentPanY = (currentPanY - (dy / viewH)).coerceIn(0f, 1f)
                    renderPreviewWithCurrentSettings()
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    view.parent?.requestDisallowInterceptTouchEvent(false)
                    true
                }
                else -> false
            }
        }
    }

    private fun setupClickListeners() {
        // Photo Picker - use standard image document/gallery selector to prevent PhotoPicker process kill on OEM ROMs
        binding.btnPickPhoto.setOnClickListener {
            try {
                pickImageGetContentLauncher.launch("image/*")
            } catch (e: Exception) {
                Log.w(TAG, "GetContent image/* failed, falling back to PickVisualMedia: ${e.message}")
                pickPhotoLauncher.launch(
                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                )
            }
        }

        // Zoom & Reset controls
        binding.btnZoomIn.setOnClickListener {
            if (currentZoomScale < 3.0f) {
                currentZoomScale += 0.25f
                renderPreviewWithCurrentSettings()
            }
        }
        binding.btnZoomOut.setOnClickListener {
            if (currentZoomScale > 1.0f) {
                currentZoomScale = (currentZoomScale - 0.25f).coerceAtLeast(1.0f)
                renderPreviewWithCurrentSettings()
            }
        }
        binding.btnResetCrop.setOnClickListener {
            currentZoomScale = 1.0f
            currentPanX = 0.5f
            currentPanY = 0.5f
            renderPreviewWithCurrentSettings()
        }

        // Install Wallpaper button
        binding.btnInstallToWatch.setOnClickListener {
            handleInstallWallpaperClicked()
        }

        // Cancel transfer button
        binding.btnCancelTransfer.setOnClickListener {
            bleService?.cancelWatchfaceUpload()
            Toast.makeText(this, "Membatalkan pemasangan...", Toast.LENGTH_SHORT).show()
        }

        // Retry transfer button
        binding.btnRetryTransfer.setOnClickListener {
            binding.layoutTransferResult.visibility = View.GONE
            val retryId = lastAttemptedWatchfaceId
            val retryBytes = lastAttemptedUploadBytes
            val svc = bleService
            if (retryId != null && retryBytes != null && svc != null && svc.syncState.value is BleSyncService.SyncState.Authenticated) {
                svc.startWatchfaceUpload(retryId, retryBytes)
            } else {
                handleInstallWallpaperClicked()
            }
        }

        // Import custom package button
        binding.btnImportPackage.setOnClickListener {
            importPackageLauncher.launch("*/*")
        }

        // Install imported package button
        binding.btnInstallImportedPackage.setOnClickListener {
            handleInstallImportedPackageClicked()
        }

        // Refresh installed watchfaces list button
        binding.btnRefreshWatchfaceList.setOnClickListener {
            bleService?.queryWatchfaceList()
            Toast.makeText(this, "Memperbarui daftar watchface...", Toast.LENGTH_SHORT).show()
        }
    }

    private fun isServiceConnected(state: BleSyncService.SyncState?): Boolean {
        return state is BleSyncService.SyncState.Authenticated ||
                state is BleSyncService.SyncState.SyncingSleep
    }

    private fun observeServiceState(service: BleSyncService) {
        serviceObserveJob?.cancel()
        serviceObserveJob = lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    service.syncState.collect { state ->
                        val isConnected = isServiceConnected(state)
                        updateConnectionUi(isConnected)
                    }
                }

                launch {
                    service.installedWatchfaces.collect { list ->
                        updateWatchfaceListUi(list)
                    }
                }

                launch {
                    service.watchfaceTransferState.collect { transferState ->
                        updateTransferStateUi(transferState)
                    }
                }
            }
        }
    }

    private fun updateConnectionUi(isConnected: Boolean) {
        val isTransferring = bleService?.watchfaceTransferState?.value?.let {
            it is WatchfaceTransferState.PreparingUpload ||
                    it is WatchfaceTransferState.Uploading ||
                    it is WatchfaceTransferState.Verifying
        } ?: false
        if (isConnected) {
            binding.tvWatchConnectionStatus.text = "Terhubung ke Redmi Watch 3"
            binding.tvWatchConnectionStatus.setTextColor(ContextCompat.getColor(this, R.color.primary_mint))
            binding.btnInstallToWatch.isEnabled = !isTransferring && (processedWallpaperBitmap != null)
            binding.btnInstallImportedPackage.isEnabled = !isTransferring && (importedPackageData != null)
        } else {
            binding.tvWatchConnectionStatus.text = "Jam tangan tidak terhubung"
            binding.tvWatchConnectionStatus.setTextColor(ContextCompat.getColor(this, R.color.on_surface_muted))
            binding.btnInstallToWatch.isEnabled = false
            binding.btnInstallImportedPackage.isEnabled = false
        }
    }

    private fun updateWatchfaceListUi(list: List<XiaomiWearProtocolEngine.WatchfaceItem>) {
        if (list.isEmpty()) {
            binding.tvEmptyWatchfaces.visibility = View.VISIBLE
            binding.rvInstalledWatchfaces.visibility = View.GONE
        } else {
            binding.tvEmptyWatchfaces.visibility = View.GONE
            binding.rvInstalledWatchfaces.visibility = View.VISIBLE
            watchfaceAdapter.submitList(list)
        }
    }

    private fun updateTransferStateUi(state: WatchfaceTransferState) {
        val isTransferring = state is WatchfaceTransferState.PreparingUpload ||
                state is WatchfaceTransferState.Uploading ||
                state is WatchfaceTransferState.Verifying

        val isConnected = isServiceConnected(bleService?.syncState?.value)
        binding.btnPickPhoto.isEnabled = !isTransferring
        binding.btnImportPackage.isEnabled = !isTransferring
        binding.btnInstallToWatch.isEnabled = !isTransferring && (processedWallpaperBitmap != null) && isConnected
        binding.btnInstallImportedPackage.isEnabled = !isTransferring && (importedPackageData != null) && isConnected

        when (state) {
            is WatchfaceTransferState.Idle -> {
                binding.layoutTransferProgress.visibility = View.GONE
            }
            is WatchfaceTransferState.QueryingList -> {
                // List is querying in background
            }
            is WatchfaceTransferState.Selecting -> {
                Toast.makeText(this, "Mengaktifkan watchface...", Toast.LENGTH_SHORT).show()
            }
            is WatchfaceTransferState.Deleting -> {
                Toast.makeText(this, "Menghapus watchface dari jam...", Toast.LENGTH_SHORT).show()
            }
            is WatchfaceTransferState.PreparingUpload -> {
                binding.layoutTransferProgress.visibility = View.VISIBLE
                binding.layoutTransferResult.visibility = View.GONE
                binding.progressBarTransfer.progress = 0
                binding.tvTransferProgressPercent.text = "0%"
                binding.tvTransferStatus.text = "Menyiapkan transfer ke jam..."
                binding.tvTransferBytes.text = "0 / ${state.totalBytes / 1024} KB"
            }
            is WatchfaceTransferState.Uploading -> {
                binding.layoutTransferProgress.visibility = View.VISIBLE
                binding.layoutTransferResult.visibility = View.GONE
                binding.progressBarTransfer.progress = state.percent
                binding.tvTransferProgressPercent.text = "${state.percent}%"
                binding.tvTransferStatus.text = "Mengunggah paket data ke Redmi Watch 3..."
                binding.tvTransferBytes.text = "${state.bytesSent / 1024} KB / ${state.totalBytes / 1024} KB"
            }
            is WatchfaceTransferState.Verifying -> {
                binding.layoutTransferProgress.visibility = View.VISIBLE
                binding.progressBarTransfer.progress = 100
                binding.tvTransferProgressPercent.text = "100%"
                binding.tvTransferStatus.text = "Memverifikasi pemasangan di jam tangan..."
            }
            is WatchfaceTransferState.Success -> {
                binding.layoutTransferProgress.visibility = View.GONE
                binding.layoutTransferResult.visibility = View.VISIBLE
                binding.imgTransferResultIcon.setImageResource(R.drawable.ic_check_circle)
                binding.imgTransferResultIcon.imageTintList = ContextCompat.getColorStateList(this, R.color.primary_mint)
                binding.tvTransferResultTitle.text = "Pemasangan Berhasil"
                binding.tvTransferResultTitle.setTextColor(ContextCompat.getColor(this, R.color.primary_mint))
                binding.tvTransferResultMsg.text = state.message
                binding.btnRetryTransfer.visibility = View.GONE
                bleService?.queryWatchfaceList()
            }
            is WatchfaceTransferState.Error -> {
                binding.layoutTransferProgress.visibility = View.GONE
                binding.layoutTransferResult.visibility = View.VISIBLE
                binding.imgTransferResultIcon.setImageResource(R.drawable.ic_cloud_upload)
                binding.imgTransferResultIcon.imageTintList = ContextCompat.getColorStateList(this, R.color.btn_coral_stroke)
                binding.tvTransferResultTitle.text = "Pemasangan Gagal"
                binding.tvTransferResultTitle.setTextColor(ContextCompat.getColor(this, R.color.btn_coral_stroke))
                binding.tvTransferResultMsg.text = state.message
                binding.btnRetryTransfer.visibility = View.VISIBLE
            }
        }
    }

    private fun loadAndProcessPhoto(uri: Uri) {
        try {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (_: Exception) {
            // Ignore if URI does not support persistable permission
        }

        // Open stream immediately on callback while URI grant & picker provider process are alive
        val directLocalFile = if (uri.scheme == "file" && !uri.path.isNullOrBlank()) {
            java.io.File(uri.path!!).takeIf { it.exists() && it.canRead() }
        } else null

        val preOpenedStream = if (directLocalFile == null) {
            try {
                contentResolver.openInputStream(uri)
            } catch (e: Exception) {
                Log.w(TAG, "Immediate openInputStream failed: ${e.message}")
                null
            }
        } else null

        lifecycleScope.launch(Dispatchers.IO) {
            val bitmap = try {
                if (directLocalFile != null) {
                    WallpaperImageProcessor.decodeSampledBitmapFromFile(
                        file = directLocalFile,
                        reqWidth = WallpaperImageProcessor.WATCH_WIDTH * 2,
                        reqHeight = WallpaperImageProcessor.WATCH_HEIGHT * 2
                    )
                } else if (preOpenedStream != null) {
                    val tempFile = java.io.File(cacheDir, "picked_wallpaper_immediate.tmp")
                    preOpenedStream.use { input ->
                        java.io.FileOutputStream(tempFile).use { output ->
                            input.copyTo(output, bufferSize = 16 * 1024)
                            output.flush()
                        }
                    }
                    WallpaperImageProcessor.decodeSampledBitmapFromFile(
                        file = tempFile,
                        reqWidth = WallpaperImageProcessor.WATCH_WIDTH * 2,
                        reqHeight = WallpaperImageProcessor.WATCH_HEIGHT * 2
                    ) ?: WallpaperImageProcessor.decodeSampledBitmap(
                        context = this@WatchfaceActivity,
                        uri = uri,
                        reqWidth = WallpaperImageProcessor.WATCH_WIDTH * 2,
                        reqHeight = WallpaperImageProcessor.WATCH_HEIGHT * 2
                    )
                } else {
                    WallpaperImageProcessor.decodeSampledBitmap(
                        context = this@WatchfaceActivity,
                        uri = uri,
                        reqWidth = WallpaperImageProcessor.WATCH_WIDTH * 2,
                        reqHeight = WallpaperImageProcessor.WATCH_HEIGHT * 2
                    )
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error decoding selected photo URI $uri", e)
                null
            }

            withContext(Dispatchers.Main) {
                if (bitmap != null) {
                    selectedRawBitmap = bitmap
                    currentZoomScale = 1.0f
                    currentPanX = 0.5f
                    currentPanY = 0.5f

                    binding.layoutPhotoControls.visibility = View.VISIBLE
                    renderPreviewWithCurrentSettings()

                    val isConnected = isServiceConnected(bleService?.syncState?.value)
                    binding.btnInstallToWatch.isEnabled = isConnected
                    Toast.makeText(
                        this@WatchfaceActivity,
                        "Foto dimuat (${bitmap.width}×${bitmap.height}). Geser gambar untuk mengatur posisi.",
                        Toast.LENGTH_SHORT
                    ).show()
                } else {
                    Toast.makeText(
                        this@WatchfaceActivity,
                        "Gagal membaca format gambar. Silakan coba pilih foto lain.",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    private fun renderPreviewWithCurrentSettings() {
        val raw = selectedRawBitmap ?: return
        if (raw.isRecycled) return

        lifecycleScope.launch(Dispatchers.Default) {
            try {
                val cropped = WallpaperImageProcessor.cropToWatchDimensions(
                    source = raw,
                    scale = currentZoomScale,
                    panFractionX = currentPanX,
                    panFractionY = currentPanY
                )

                val now = Date()
                val timeFmt = SimpleDateFormat("HH:mm", Locale.getDefault()).format(now)
                val dateFmt = SimpleDateFormat("EEE, d MMM", Locale("id", "ID")).format(now)

                val preview = WallpaperImageProcessor.renderClockOverlayPreview(
                    wallpaper = cropped,
                    timeText = timeFmt,
                    dateText = dateFmt,
                    stepText = "6.000"
                )

                withContext(Dispatchers.Main) {
                    processedWallpaperBitmap = cropped
                    processedPreviewBitmap = preview
                    binding.imgWatchPreview.setImageBitmap(preview)
                    binding.imgWatchPreview.visibility = View.VISIBLE
                    binding.layoutPlaceholderPreview.visibility = View.GONE

                    val isConnected = isServiceConnected(bleService?.syncState?.value)
                    binding.btnInstallToWatch.isEnabled = isConnected
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to render preview", e)
            }
        }
    }

    private fun loadAndValidatePackage(uri: Uri) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val cr = contentResolver
                val filename = getFileNameFromUri(uri)
                val bytes = cr.openInputStream(uri)?.use { it.readBytes() }

                if (bytes == null || bytes.isEmpty()) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@WatchfaceActivity, "Gagal membaca file paket.", Toast.LENGTH_SHORT).show()
                    }
                    return@launch
                }

                val validation = WatchfacePackageValidator.validate(filename, bytes)
                withContext(Dispatchers.Main) {
                    when (validation) {
                        is WatchfacePackageValidator.ValidationResult.Valid -> {
                            importedPackageData = bytes
                            importedValidationResult = validation

                            binding.layoutImportedPackageInfo.visibility = View.VISIBLE
                            val typeLabel = if (validation.isZip) "ZIP" else "BIN"
                            binding.tvImportedPackageName.text = "Paket: ${validation.name} ($typeLabel)"
                            binding.tvImportedPackageDetails.text = "Ukuran: ${bytes.size / 1024} KB | MD5: ${validation.md5Hex.take(12)}..."
                            binding.btnInstallImportedPackage.isEnabled = isServiceConnected(bleService?.syncState?.value)

                            Toast.makeText(this@WatchfaceActivity, "Paket watchface valid untuk Redmi Watch 3!", Toast.LENGTH_SHORT).show()
                        }
                        is WatchfacePackageValidator.ValidationResult.Invalid -> {
                            importedPackageData = null
                            importedValidationResult = null
                            binding.layoutImportedPackageInfo.visibility = View.GONE

                            AlertDialog.Builder(this@WatchfaceActivity)
                                .setTitle("Paket Ditolak")
                                .setMessage("File tidak dapat digunakan sebagai watchface:\n\n${validation.reason}")
                                .setPositiveButton("Mengerti", null)
                                .show()
                        }
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@WatchfaceActivity, "Error validasi: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleDebugIntentIfPresent(intent)
    }

    private fun handleDebugIntentIfPresent(incomingIntent: Intent?) {
        val pkgPath = incomingIntent?.getStringExtra("auto_test_package_path")
        val photoUriStr = incomingIntent?.getStringExtra("auto_test_photo_uri")
        if (!pkgPath.isNullOrBlank()) {
            incomingIntent.removeExtra("auto_test_package_path")
            val file = java.io.File(pkgPath)
            if (file.exists() && file.canRead()) {
                loadAndValidatePackage(Uri.fromFile(file))
            }
        } else if (!photoUriStr.isNullOrBlank()) {
            incomingIntent.removeExtra("auto_test_photo_uri")
            loadAndProcessPhoto(Uri.parse(photoUriStr))
        }
    }

    private fun handleInstallWallpaperClicked() {
        val wallpaper = processedWallpaperBitmap
        if (wallpaper == null) {
            Toast.makeText(this, "Pilih foto terlebih dahulu.", Toast.LENGTH_SHORT).show()
            return
        }

        val service = bleService
        if (service == null || !isServiceConnected(service.syncState.value)) {
            Toast.makeText(this, "Jam tangan belum terhubung.", Toast.LENGTH_SHORT).show()
            return
        }

        val preview = processedPreviewBitmap ?: wallpaper
        binding.btnInstallToWatch.isEnabled = false
        binding.layoutTransferResult.visibility = View.GONE
        binding.layoutTransferProgress.visibility = View.VISIBLE
        binding.progressBarTransfer.progress = 0
        binding.tvTransferProgressPercent.text = "0%"
        binding.tvTransferStatus.text = "Menyusun paket biner wallpaper LVGL (390×450)..."
        binding.tvTransferBytes.text = "Memproses foto..."

        lifecycleScope.launch(Dispatchers.Default) {
            try {
                val binBytes = WallpaperBinPackager.buildFromAssetTemplate(
                    context = this@WatchfaceActivity,
                    wallpaperBitmap = wallpaper,
                    thumbnailSourceBitmap = preview
                )
                val wfId = WallpaperBinPackager.DEFAULT_WALLPAPER_WATCHFACE_ID
                withContext(Dispatchers.Main) {
                    lastAttemptedWatchfaceId = wfId
                    lastAttemptedUploadBytes = binBytes
                    service.startWatchfaceUpload(wfId, binBytes)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Gagal menyusun paket biner wallpaper", e)
                withContext(Dispatchers.Main) {
                    binding.layoutTransferProgress.visibility = View.GONE
                    binding.btnInstallToWatch.isEnabled = true
                    Toast.makeText(
                        this@WatchfaceActivity,
                        "Gagal menyusun paket wallpaper: ${e.message}",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    private fun handleInstallImportedPackageClicked() {
        val data = importedPackageData ?: return
        val validation = importedValidationResult as? WatchfacePackageValidator.ValidationResult.Valid ?: return
        val service = bleService ?: return

        if (!isServiceConnected(service.syncState.value)) {
            Toast.makeText(this, "Jam tangan belum terhubung.", Toast.LENGTH_SHORT).show()
            return
        }

        AlertDialog.Builder(this)
            .setTitle("Konfirmasi Pasang Watchface")
            .setMessage("Apakah Anda ingin memasang '${validation.name}' (${data.size / 1024} KB) ke Redmi Watch 3?\n\nProses transfer akan berlangsung beberapa saat.")
            .setPositiveButton("Pasang") { _, _ ->
                lifecycleScope.launch(Dispatchers.Default) {
                    val uploadBytes = WatchfacePackageValidator.extractUploadableBinary(data)
                    val watchfaceId = validation.id.filter { it.isLetterOrDigit() || it == '_' }.take(24).ifBlank {
                        WallpaperBinPackager.DEFAULT_WALLPAPER_WATCHFACE_ID
                    }
                    withContext(Dispatchers.Main) {
                        lastAttemptedWatchfaceId = watchfaceId
                        lastAttemptedUploadBytes = uploadBytes
                        service.startWatchfaceUpload(watchfaceId, uploadBytes)
                    }
                }
            }
            .setNegativeButton("Batal", null)
            .show()
    }

    private fun showApplyConfirmDialog(item: XiaomiWearProtocolEngine.WatchfaceItem) {
        AlertDialog.Builder(this)
            .setTitle("Ganti Tampilan Jam")
            .setMessage("Gunakan watchface '${item.name}' di Redmi Watch 3?")
            .setPositiveButton("Gunakan") { _, _ ->
                bleService?.setActiveWatchface(item.id)
            }
            .setNegativeButton("Batal", null)
            .show()
    }

    private fun showDeleteConfirmDialog(item: XiaomiWearProtocolEngine.WatchfaceItem) {
        AlertDialog.Builder(this)
            .setTitle("Hapus Watchface")
            .setMessage("Hapus '${item.name}' dari memori jam tangan?")
            .setPositiveButton("Hapus") { _, _ ->
                bleService?.deleteWatchface(item.id)
            }
            .setNegativeButton("Batal", null)
            .show()
    }

    private fun getFileNameFromUri(uri: Uri): String {
        var name = "custom_watchface.bin"
        contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (nameIndex != -1 && cursor.moveToFirst()) {
                name = cursor.getString(nameIndex) ?: name
            }
        }
        return name
    }
}
