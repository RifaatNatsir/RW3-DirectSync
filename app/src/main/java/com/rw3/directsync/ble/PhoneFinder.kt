package com.rw3.directsync.ble

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import androidx.core.app.NotificationCompat
import com.rw3.directsync.R
import com.rw3.directsync.ui.MainActivity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** All calls on main. A bounded alarm without modifying the user's stream volume or DND. */
class PhoneFinder(private val context: Context, private val onTimeout: () -> Unit, private val log: (String) -> Unit) {
    private val handler = Handler(Looper.getMainLooper())
    private val active = MutableStateFlow(false)
    val ringing = active.asStateFlow()
    private var player: MediaPlayer? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private val vibrator = context.getSystemService(Vibrator::class.java)
    private val manager = context.getSystemService(NotificationManager::class.java)
    private val audio = context.getSystemService(AudioManager::class.java)
    private val attributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM)
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()
    private val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
        .setAudioAttributes(attributes).setOnAudioFocusChangeListener { change ->
            if (change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) onTimeout()
        }.build()
    private val timeout = Runnable { onTimeout() }

    fun start() {
        if (active.value) return // Repeated watch requests cannot extend the 60-second deadline.
        active.value = true
        handler.postDelayed(timeout, 60_000)
        try {
            wakeLock = context.getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "RW3:PhoneFinder").apply { acquire(65_000) }
            audio.requestAudioFocus(focus)
            val alarm = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            val instance = MediaPlayer()
            player = instance
            instance.setAudioAttributes(attributes)
            instance.setDataSource(context, alarm)
            instance.isLooping = true
            instance.setOnPreparedListener { if (player === it && active.value) it.start() }
            instance.setOnErrorListener { media, _, _ ->
                if (player === media) { log("Suara pencarian HP tidak dapat diputar; periksa volume alarm."); onTimeout() }
                true
            }
            instance.prepareAsync()
        } catch (e: Exception) { log("Suara pencarian HP tidak tersedia: ${e.message}"); player?.release(); player = null }
        try { vibrator?.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 500, 500), 0), attributes) }
        catch (_: Exception) { /* Some devices have no vibration hardware. */ }
        val channel = NotificationChannel("find_phone", "Pencarian HP", NotificationManager.IMPORTANCE_HIGH).apply {
            setSound(null, null); enableVibration(false); lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
        }
        manager.createNotificationChannel(channel)
        val stop = PendingIntent.getService(context, 901, Intent(context, BleSyncService::class.java)
            .setAction(BleSyncService.ACTION_STOP_FIND_PHONE), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val open = PendingIntent.getActivity(context, 902, Intent(context, MainActivity::class.java)
            .putExtra("open_device_finder", true).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        try {
            manager.notify(901, NotificationCompat.Builder(context, "find_phone")
                .setSmallIcon(R.drawable.ic_stat_directsync).setContentTitle("Smartwatch sedang mencari HP ini")
                .setContentText("Ketuk Hentikan untuk mematikan bunyi dan getaran. Batas 60 detik.")
                .setContentIntent(open).setOngoing(true).setCategory(NotificationCompat.CATEGORY_ALARM)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC).setOnlyAlertOnce(true)
                .addAction(R.drawable.ic_power, "Hentikan", stop).build())
        } catch (_: SecurityException) { log("Izinkan notifikasi agar tombol Hentikan tersedia di layar kunci.") }
    }

    fun stop() {
        handler.removeCallbacks(timeout)
        active.value = false
        player?.let { runCatching { it.release() } }; player = null
        vibrator?.cancel()
        audio.abandonAudioFocusRequest(focus)
        wakeLock?.let { if (it.isHeld) it.release() }; wakeLock = null
        manager.cancel(901)
    }
}
