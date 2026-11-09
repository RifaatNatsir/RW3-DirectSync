package com.rw3.directsync.sync

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** A handoff survives process recreation and is resumed only by an explicit user action. */
class DirectSyncMode private constructor(context: Context) {
    private val prefs = context.getSharedPreferences("directsync_connection_mode", Context.MODE_PRIVATE)
    private val _paused = MutableStateFlow(prefs.getBoolean("mi_fitness", false))
    val paused = _paused.asStateFlow()
    @Synchronized fun pause() {
        check(prefs.edit().putBoolean("mi_fitness", true).putBoolean("return_prompt", true).commit())
        _paused.value = true
    }
    @Synchronized fun resume() {
        check(prefs.edit().putBoolean("mi_fitness", false).putBoolean("return_prompt", false).commit())
        _paused.value = false
    }
    @Synchronized fun consumeReturnPrompt(): Boolean {
        if (!_paused.value || !prefs.getBoolean("return_prompt", false)) return false
        prefs.edit().putBoolean("return_prompt", false).apply()
        return true
    }
    companion object {
        @Volatile private var instance: DirectSyncMode? = null
        fun get(context: Context): DirectSyncMode = instance ?: synchronized(this) {
            instance ?: DirectSyncMode(context.applicationContext).also { instance = it }
        }
    }
}
