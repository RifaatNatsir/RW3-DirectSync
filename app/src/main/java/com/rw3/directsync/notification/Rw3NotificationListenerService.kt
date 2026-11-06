package com.rw3.directsync.notification

import android.annotation.SuppressLint
import android.app.Notification
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import android.util.LruCache
import com.rw3.directsync.ble.BleSyncService

/**
 * Android NotificationListenerService for intercepting smartphone notifications
 * (WhatsApp, Telegram, SMS, incoming calls, system alerts) and forwarding them
 * in real-time to Redmi Watch 3 over the encrypted Xiaomi Wear protobuf channel.
 */
@SuppressLint("OverrideAbstract")
class Rw3NotificationListenerService : NotificationListenerService() {

    companion object {
        private const val TAG = "Rw3NotifListener"
        private const val PREFS_NAME = "rw3_prefs"
        private const val PREF_NOTIF_FORWARDING_ENABLED = "pref_notif_forwarding_enabled"
        private const val PREF_CALL_FORWARDING_ENABLED = "pref_call_forwarding_enabled"
        const val PREF_WHITELIST_MODE_ENABLED = "pref_notif_whitelist_mode_enabled"
        const val PREF_WHITELISTED_PACKAGES = "pref_notif_whitelisted_packages"

        // Default whitelist includes popular messaging, chat, email, and social apps in Indonesia
        val DEFAULT_WHITELIST: Set<String> = setOf(
            "com.whatsapp",
            "com.whatsapp.w4b",                   // WhatsApp Business
            "org.telegram.messenger",
            "org.telegram.plus",
            "com.google.android.apps.messaging",  // Google Messages
            "com.android.mms",                    // SMS
            "com.google.android.gm",              // Gmail
            "com.instagram.android",              // Instagram
            "com.discord",                        // Discord
            "com.twitter.android",                // Twitter / X
            "com.x.android",
            "com.facebook.orca",                  // Messenger
            "jp.naver.line.android",              // LINE
            "com.google.android.dialer",          // Dialer / Calls
            "com.android.dialer"
        )

        // Deduplication cache: avoids spamming the watch when an app rapidly updates the same notification
        private val recentNotifications = LruCache<String, Long>(64)
        private const val DEDUPE_WINDOW_MS = 2000L

        fun isNotificationAccessGranted(context: Context): Boolean {
            val flat = Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners") ?: return false
            val myComponent = ComponentName(context, Rw3NotificationListenerService::class.java).flattenToString()
            return flat.contains(myComponent) || flat.contains(context.packageName)
        }

        fun openNotificationAccessSettings(context: Context) {
            val intent = try {
                Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
            } catch (_: Exception) {
                Intent(Settings.ACTION_SETTINGS).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
            }
            context.startActivity(intent)
        }

        fun isNotificationForwardingEnabled(context: Context): Boolean {
            val sp = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            return sp.getBoolean(PREF_NOTIF_FORWARDING_ENABLED, true)
        }

        fun setNotificationForwardingEnabled(context: Context, enabled: Boolean) {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(PREF_NOTIF_FORWARDING_ENABLED, enabled)
                .apply()
        }

        fun isCallForwardingEnabled(context: Context): Boolean {
            val sp = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            return sp.getBoolean(PREF_CALL_FORWARDING_ENABLED, true)
        }

        fun setCallForwardingEnabled(context: Context, enabled: Boolean) {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(PREF_CALL_FORWARDING_ENABLED, enabled)
                .apply()
        }

        fun isWhitelistModeEnabled(context: Context): Boolean {
            val sp = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            return sp.getBoolean(PREF_WHITELIST_MODE_ENABLED, true)
        }

        fun setWhitelistModeEnabled(context: Context, enabled: Boolean) {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(PREF_WHITELIST_MODE_ENABLED, enabled)
                .apply()
        }

        fun getWhitelistedPackages(context: Context): Set<String> {
            val sp = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            return sp.getStringSet(PREF_WHITELISTED_PACKAGES, null) ?: DEFAULT_WHITELIST
        }

        fun setWhitelistedPackages(context: Context, packages: Set<String>) {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putStringSet(PREF_WHITELISTED_PACKAGES, HashSet(packages))
                .apply()
        }

        fun isPackageAllowed(context: Context, packageName: String): Boolean {
            if (!isWhitelistModeEnabled(context)) return true
            val allowed = getWhitelistedPackages(context)
            return allowed.contains(packageName)
        }
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        Log.i(TAG, "Rw3NotificationListenerService terhubung ke Android Notification Manager.")
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        Log.i(TAG, "Rw3NotificationListenerService terputus.")
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        super.onNotificationPosted(sbn)
        if (sbn == null) return

        val pkgName = sbn.packageName ?: return

        // 1. Ignore own notifications to prevent infinite loops
        if (pkgName == packageName) return

        val notification = sbn.notification ?: return
        val extras = notification.extras

        // 2. Identify if this is an incoming phone call
        val category = notification.category
        val isCall = (category == Notification.CATEGORY_CALL)

        // 3. Filter out ongoing non-alert background notifications (e.g. downloads, background tasks)
        val isOngoing = sbn.isOngoing || (notification.flags and Notification.FLAG_FOREGROUND_SERVICE != 0)
        if (isOngoing && !isCall) {
            return
        }

        // 4. Check user preferences for notification forwarding
        if (isCall) {
            if (!isCallForwardingEnabled(this)) return
        } else {
            if (!isNotificationForwardingEnabled(this)) return
            if (!isPackageAllowed(this, pkgName)) {
                Log.d(TAG, "Notifikasi dari '$pkgName' diabaikan (tidak ada dalam whitelist).")
                return
            }
        }

        // 5. Extract Title & Body cleanly
        val title = (extras?.getCharSequence(Notification.EXTRA_TITLE)?.toString()
            ?: extras?.getCharSequence(Notification.EXTRA_TITLE_BIG)?.toString()
            ?: "").trim()

        val body = (extras?.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()
            ?: extras?.getCharSequence(Notification.EXTRA_TEXT)?.toString()
            ?: extras?.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString()
            ?: notification.tickerText?.toString()
            ?: "").trim()

        // Ignore empty / ghost notifications
        if (title.isEmpty() && body.isEmpty()) return

        // 6. Deduplicate rapid duplicate updates within 2 seconds
        val dedupeKey = "$pkgName:${sbn.id}:$title:$body"
        val now = System.currentTimeMillis()
        synchronized(recentNotifications) {
            val lastSeen = recentNotifications.get(dedupeKey) ?: 0L
            if (now - lastSeen < DEDUPE_WINDOW_MS) {
                return
            }
            recentNotifications.put(dedupeKey, now)
        }

        // 7. Resolve friendly app label
        val appName = try {
            val appInfo = packageManager.getApplicationInfo(pkgName, 0)
            packageManager.getApplicationLabel(appInfo).toString()
        } catch (_: Exception) {
            pkgName.substringAfterLast('.').replaceFirstChar { it.uppercase() }
        }

        Log.d(TAG, "Meneruskan notifikasi dari $appName: $title - ${body.take(30)}")

        // 8. Dispatch to BleSyncService
        BleSyncService.instance?.forwardNotification(
            id = sbn.id,
            packageName = pkgName,
            appName = appName,
            title = if (title.isNotEmpty()) title else appName,
            body = body,
            timestampMillis = if (sbn.postTime > 0) sbn.postTime else now,
            isCall = isCall,
            key = sbn.key
        )
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        super.onNotificationRemoved(sbn)
        if (sbn == null) return
        if (sbn.packageName == packageName) return

        BleSyncService.instance?.dismissNotificationOnWatch(
            id = sbn.id,
            packageName = sbn.packageName,
            key = sbn.key
        )
    }
}

