package com.rw3.directsync.data

import android.content.Context
import com.rw3.directsync.ble.parser.XiaomiSleepFileParser
import com.rw3.directsync.data.db.Rw3Database
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Recovers older verified downloads on app startup, including while the watch is offline. */
object SleepHistoryCache {
    suspend fun recover(context: Context) = withContext(Dispatchers.IO) {
        val dao = Rw3Database.getDatabase(context).sleepStageDao()
        File(context.cacheDir, "activity_diagnostics").listFiles()?.forEach { file ->
            if (file.length() !in 13..4 * 1024 * 1024) return@forEach
            val parsed = runCatching {
                val bytes = file.readBytes()
                if (!XiaomiSleepFileParser.isSleepFile(bytes.copyOfRange(0, 7))) null
                else XiaomiSleepFileParser.parse(bytes)
            }.getOrNull() ?: return@forEach
            dao.saveVerifiedSleep(parsed.session, parsed.stages)
        }
    }
}
