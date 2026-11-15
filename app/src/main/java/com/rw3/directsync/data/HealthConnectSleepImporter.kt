package com.rw3.directsync.data

import android.content.Context
import android.os.Build
import androidx.annotation.RequiresApi

/** Backwards-compatible delegate keeping sleep-only callers compiling cleanly. */
@RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
class HealthConnectSleepImporter(private val context: Context) {
    companion object {
        const val READ_SLEEP = MiFitnessHealthConnectImporter.READ_SLEEP
    }

    suspend fun importHistory(): Int = MiFitnessHealthConnectImporter(context).importAll().totalItems
}
