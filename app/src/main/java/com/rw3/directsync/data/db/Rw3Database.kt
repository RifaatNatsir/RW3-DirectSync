package com.rw3.directsync.data.db

import android.content.Context
import com.rw3.directsync.data.db.dao.ActivityRecordDao
import com.rw3.directsync.data.db.entity.ActivityRecordEntity
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.rw3.directsync.data.db.dao.HeartRateDao
import com.rw3.directsync.data.db.dao.SleepStageDao
import com.rw3.directsync.data.db.entity.HeartRateEntity
import com.rw3.directsync.data.db.entity.SleepStageEntity
import com.rw3.directsync.data.db.entity.SleepSessionEntity

@Database(
    entities = [
        HeartRateEntity::class,
        SleepStageEntity::class,
        SleepSessionEntity::class,
        ActivityRecordEntity::class
    ],
    version = 3,
    exportSchema = false
)
abstract class Rw3Database : RoomDatabase() {

    abstract fun activityRecordDao(): ActivityRecordDao
    abstract fun heartRateDao(): HeartRateDao
    abstract fun sleepStageDao(): SleepStageDao

    companion object {
        private const val DATABASE_NAME = "rw3_directsync.db"

        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Preserve old records, but only files verified by the new parser are used/exported.
                db.execSQL("ALTER TABLE sleep_stage_records ADD COLUMN source_file_id TEXT")
                db.execSQL("""CREATE TABLE IF NOT EXISTS sleep_sessions (
                    startTimestamp INTEGER NOT NULL PRIMARY KEY,
                    endTimestamp INTEGER NOT NULL, totalMinutes INTEGER NOT NULL,
                    deepMinutes INTEGER NOT NULL, lightMinutes INTEGER NOT NULL,
                    remMinutes INTEGER NOT NULL, awakeMinutes INTEGER NOT NULL,
                    sleepScore INTEGER, averageHeartRate INTEGER, sourceFileId TEXT NOT NULL
                )""")
            }
        }

        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""CREATE TABLE IF NOT EXISTS activity_records (
                    id TEXT NOT NULL PRIMARY KEY, category TEXT NOT NULL, timestamp INTEGER NOT NULL,
                    endTimestamp INTEGER, steps INTEGER, calories INTEGER, restingHeartRate INTEGER,
                    averageHeartRate INTEGER, minHeartRate INTEGER, maxHeartRate INTEGER,
                    spo2 INTEGER, stress INTEGER, standingHours INTEGER, activeSeconds INTEGER, sportType INTEGER,
                    sourceFileId TEXT NOT NULL, formatVersion INTEGER NOT NULL, syncedToWebhook INTEGER NOT NULL
                )""")
            }
        }

        @Volatile
        private var INSTANCE: Rw3Database? = null

        fun getDatabase(context: Context): Rw3Database {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    Rw3Database::class.java,
                    DATABASE_NAME
                )
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}

