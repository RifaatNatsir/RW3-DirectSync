package com.rw3.directsync.data

import android.content.Context
import com.rw3.directsync.ble.parser.XiaomiActivityFileParser
import com.rw3.directsync.ble.parser.XiaomiHeartRateHistoryParser
import com.rw3.directsync.data.db.Rw3Database
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

object ActivityHistoryCache {
    suspend fun recover(context: Context) = withContext(Dispatchers.IO) {
        val db = Rw3Database.getDatabase(context)
        File(context.cacheDir, "activity_diagnostics").listFiles()?.forEach { file ->
            if (file.length() !in 13L..4L * 1024 * 1024) return@forEach
            val data = runCatching { file.readBytes() }.getOrNull() ?: return@forEach
            val id = data.copyOfRange(0, 7)
            if (XiaomiHeartRateHistoryParser.accepts(id)) {
                val parsed = runCatching { XiaomiHeartRateHistoryParser.parseDailyDetails(data) }.getOrNull() ?: return@forEach
                db.dailyActivityMinuteDao().merge(parsed.minutes)
                db.activityRecordDao().insert(listOf(parsed.summary))
            } else if (XiaomiActivityFileParser.accepts(id) && id[6] == 1.toByte()) {
                val records = runCatching { XiaomiActivityFileParser.parse(data) }.getOrNull() ?: return@forEach
                db.activityRecordDao().insert(records)
            }
        }
    }
}
