package com.rw3.directsync.ble.parser

import com.rw3.directsync.crypto.XiaomiAuthCrypto
import com.rw3.directsync.data.db.entity.SleepStageEntity
import java.util.Calendar
import java.util.TimeZone

/**
 * Parses raw Activity and Sleep logs streamed from Redmi Watch 3 over
 * Huami Activity Data Characteristic (0x00000005) & Activity Control Characteristic (0x00000004).
 *
 * Reference: Gadgetbridge HuamiActivityDataParser & Huami2021Service.
 */
object XiaomiSleepParser {

    // Huami Control Commands
    const val CMD_FETCH_ACTIVITY_DATA: Byte = 0x01
    const val CMD_CONFIRM_ACTIVITY_DATA: Byte = 0x03
    const val RESPONSE_CODE: Byte = 0x10
    const val STATUS_SUCCESS: Byte = 0x01
    const val STATUS_NO_DATA: Byte = 0x02
    const val STATUS_FINISHED: Byte = 0x03

    // Huami Sample Type Codes for Sleep Detection (1-minute sample buckets)
    const val TYPE_NOT_WORN = 0
    const val TYPE_ACTIVITY_SLOW = 1
    const val TYPE_ACTIVITY_FAST = 2
    const val TYPE_SLEEP_LIGHT = 112 // 0x70
    const val TYPE_SLEEP_DEEP = 113  // 0x71
    const val TYPE_SLEEP_REM = 114   // 0x72
    const val TYPE_SLEEP_AWAKE = 115 // 0x73

    const val SAMPLE_BYTE_SIZE = 4 // Standard Huami activity sample is 4 bytes (Type, Intensity, Steps, HR)

    /**
     * Intermediate data point representing one 1-minute bucket.
     */
    data class RawActivitySample(
        val timestamp: Long,
        val rawType: Int,
        val intensity: Int,
        val steps: Int,
        val heartRate: Int?
    ) {
        val isSleep: Boolean
            get() = rawType in TYPE_SLEEP_LIGHT..TYPE_SLEEP_AWAKE

        val sleepStageName: String?
            get() = when (rawType) {
                TYPE_SLEEP_DEEP -> "DEEP"
                TYPE_SLEEP_LIGHT -> "LIGHT"
                TYPE_SLEEP_REM -> "REM"
                TYPE_SLEEP_AWAKE -> "AWAKE"
                else -> null
            }
    }

    /**
     * Builds the command to fetch activity and sleep data from the watch since a given timestamp.
     *
     * Packet structure (9 bytes):
     * [0x01, 0x01, year_low, year_high, month (1..12), day, hour, minute, tz_offset]
     */
    fun buildFetchActivityDataCommand(sinceEpochMillis: Long): ByteArray {
        val cal = Calendar.getInstance(TimeZone.getDefault()).apply {
            timeInMillis = sinceEpochMillis
        }

        val year = cal.get(Calendar.YEAR)
        val month = (cal.get(Calendar.MONTH) + 1).toByte()
        val day = cal.get(Calendar.DAY_OF_MONTH).toByte()
        val hour = cal.get(Calendar.HOUR_OF_DAY).toByte()
        val minute = cal.get(Calendar.MINUTE).toByte()
        val tzOffset = ((cal.get(Calendar.ZONE_OFFSET) + cal.get(Calendar.DST_OFFSET)) / (1000 * 60 * 15)).toByte() // in 15-min quarters

        return byteArrayOf(
            CMD_FETCH_ACTIVITY_DATA,
            0x01, // Sub-command fetch
            (year and 0xFF).toByte(),
            ((year shr 8) and 0xFF).toByte(),
            month,
            day,
            hour,
            minute,
            tzOffset
        )
    }

    /**
     * Builds the acknowledgement command confirming receipt of activity/sleep data.
     */
    fun buildConfirmActivityDataCommand(): ByteArray {
        return byteArrayOf(CMD_CONFIRM_ACTIVITY_DATA)
    }

    /**
     * Parses an incoming chunk of activity data bytes into raw minute-by-minute samples.
     *
     * In some Huami / Redmi firmwares, the payload chunks are encrypted in 16-byte blocks
     * using the AES-128 Auth Key. Set encrypted only when confirmed by protocol metadata.
     */
    fun parseActivityDataPayload(
        payload: ByteArray,
        startEpochMillis: Long,
        authKeyHex: String?,
        encrypted: Boolean = false
    ): List<RawActivitySample> {
        // Encryption must come from protocol metadata, never from payload length.
        val dataBytes = if (encrypted) {
            requireNotNull(authKeyHex) { "Encrypted activity data requires an Auth Key" }
            XiaomiAuthCrypto.decryptActivityPayload(payload, authKeyHex)
        } else {
            payload
        }
        require(dataBytes.size % SAMPLE_BYTE_SIZE == 0) { "Incomplete activity sample" }

        val samples = mutableListOf<RawActivitySample>()
        val sampleCount = dataBytes.size / SAMPLE_BYTE_SIZE

        for (i in 0 until sampleCount) {
            val offset = i * SAMPLE_BYTE_SIZE
            val type = dataBytes[offset].toInt() and 0xFF
            val intensity = dataBytes[offset + 1].toInt() and 0xFF
            val steps = dataBytes[offset + 2].toInt() and 0xFF
            val hrRaw = dataBytes[offset + 3].toInt() and 0xFF
            val hr = if (hrRaw in 30..240) hrRaw else null

            // Each consecutive sample represents a 1-minute bucket (60,000 ms)
            val sampleTime = startEpochMillis + (i * 60_000L)

            samples.add(
                RawActivitySample(
                    timestamp = sampleTime,
                    rawType = type,
                    intensity = intensity,
                    steps = steps,
                    heartRate = hr
                )
            )
        }

        return samples
    }

    /**
     * Aggregates contiguous 1-minute sleep samples (LIGHT, DEEP, REM, AWAKE)
     * into consolidated [SleepStageEntity] records for storage in Room Database.
     */
    fun aggregateSleepStages(samples: List<RawActivitySample>): List<SleepStageEntity> {
        val sleepSamples = samples.filter { it.isSleep }.distinctBy { it.timestamp }.sortedBy { it.timestamp }
        if (sleepSamples.isEmpty()) return emptyList()

        val entities = mutableListOf<SleepStageEntity>()
        var currentStageName = sleepSamples[0].sleepStageName ?: "UNKNOWN"
        var currentRawType = sleepSamples[0].rawType
        var segmentStart = sleepSamples[0].timestamp
        var segmentEnd = segmentStart + 60_000L
        var minuteCount = 1

        for (i in 1 until sleepSamples.size) {
            val sample = sleepSamples[i]
            val stageName = sample.sleepStageName ?: "UNKNOWN"

            // Check if contiguous minute and same sleep stage
            val isContiguous = sample.timestamp == segmentEnd

            if (isContiguous && stageName == currentStageName) {
                segmentEnd = sample.timestamp + 60_000L
                minuteCount++
            } else {
                // Save completed sleep segment
                entities.add(
                    SleepStageEntity(
                        startTimestamp = segmentStart,
                        endTimestamp = segmentEnd,
                        stage = currentStageName,
                        durationMinutes = minuteCount,
                        rawType = currentRawType,
                        syncedToWebhook = false
                    )
                )

                // Start new segment
                currentStageName = stageName
                currentRawType = sample.rawType
                segmentStart = sample.timestamp
                segmentEnd = segmentStart + 60_000L
                minuteCount = 1
            }
        }

        // Add last segment
        entities.add(
            SleepStageEntity(
                startTimestamp = segmentStart,
                endTimestamp = segmentEnd,
                stage = currentStageName,
                durationMinutes = minuteCount,
                rawType = currentRawType,
                syncedToWebhook = false
            )
        )

        return entities
    }
}

