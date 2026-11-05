package com.rw3.directsync.ble.parser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class XiaomiSleepParserTest {

    @Test
    fun buildFetchActivityDataCommand_generatesNineBytePacket() {
        val testEpoch = 1700000000000L
        val cmd = XiaomiSleepParser.buildFetchActivityDataCommand(testEpoch)
        assertEquals(9, cmd.size)
        assertEquals(XiaomiSleepParser.CMD_FETCH_ACTIVITY_DATA, cmd[0])
        assertEquals(0x01.toByte(), cmd[1])
    }

    @Test
    fun parseActivityDataPayload_correctlyExtractsSampleBuckets() {
        // Build 2 samples (8 bytes total):
        // Sample 1: Type 112 (SLEEP_LIGHT), Intensity 10, Steps 0, HR 65
        // Sample 2: Type 113 (SLEEP_DEEP), Intensity 5, Steps 0, HR 58
        val payload = byteArrayOf(
            112.toByte(), 10, 0, 65,
            113.toByte(), 5, 0, 58
        )
        val startEpoch = 1700000000000L
        val samples = XiaomiSleepParser.parseActivityDataPayload(
            payload = payload,
            startEpochMillis = startEpoch,
            authKeyHex = null
        )

        assertEquals(2, samples.size)
        assertEquals(112, samples[0].rawType)
        assertEquals("LIGHT", samples[0].sleepStageName)
        assertEquals(65, samples[0].heartRate)
        assertEquals(startEpoch, samples[0].timestamp)

        assertEquals(113, samples[1].rawType)
        assertEquals("DEEP", samples[1].sleepStageName)
        assertEquals(58, samples[1].heartRate)
        assertEquals(startEpoch + 60_000L, samples[1].timestamp)
    }

    @Test
    fun aggregateSleepStages_groupsContiguousMinutes() {
        val baseTime = 1700000000000L
        val rawSamples = listOf(
            // 3 contiguous minutes of LIGHT sleep
            XiaomiSleepParser.RawActivitySample(baseTime, 112, 10, 0, 65),
            XiaomiSleepParser.RawActivitySample(baseTime + 60_000L, 112, 10, 0, 64),
            XiaomiSleepParser.RawActivitySample(baseTime + 120_000L, 112, 10, 0, 66),
            // Followed by 2 contiguous minutes of DEEP sleep
            XiaomiSleepParser.RawActivitySample(baseTime + 180_000L, 113, 2, 0, 55),
            XiaomiSleepParser.RawActivitySample(baseTime + 240_000L, 113, 2, 0, 54)
        )

        val stages = XiaomiSleepParser.aggregateSleepStages(rawSamples)
        assertEquals(2, stages.size)

        // Stage 1: LIGHT (3 mins)
        assertEquals("LIGHT", stages[0].stage)
        assertEquals(3, stages[0].durationMinutes)
        assertEquals(baseTime, stages[0].startTimestamp)
        assertEquals(baseTime + 180_000L, stages[0].endTimestamp)

        // Stage 2: DEEP (2 mins)
        assertEquals("DEEP", stages[1].stage)
        assertEquals(2, stages[1].durationMinutes)
        assertEquals(baseTime + 180_000L, stages[1].startTimestamp)
        assertEquals(baseTime + 300_000L, stages[1].endTimestamp)
    }

    @Test
    fun plaintextAlignedToAesBlockIsNotDecrypted() {
        val payload = byteArrayOf(112, 0, 0, 60, 112, 0, 0, 61, 113, 0, 0, 62, 114, 0, 0, 63)
        val samples = XiaomiSleepParser.parseActivityDataPayload(payload, 0L, "00112233445566778899aabbccddeeff")
        assertEquals(listOf(112, 112, 113, 114), samples.map { it.rawType })
    }

    @Test
    fun encryptedPayloadUsesAesDecryption() {
        val key = "00112233445566778899aabbccddeeff"
        val plaintext = byteArrayOf(112, 0, 0, 60, 112, 0, 0, 61, 113, 0, 0, 62, 114, 0, 0, 63)
        val encrypted = com.rw3.directsync.crypto.XiaomiAuthCrypto.encryptChallenge(plaintext, key)
        val samples = XiaomiSleepParser.parseActivityDataPayload(encrypted, 0L, key, encrypted = true)
        assertEquals(listOf(112, 112, 113, 114), samples.map { it.rawType })
    }

    @Test(expected = IllegalArgumentException::class)
    fun incompleteSampleIsRejected() {
        XiaomiSleepParser.parseActivityDataPayload(byteArrayOf(112, 0, 0), 0L, null)
    }

    @Test
    fun aggregationDoesNotBridgeMissingMinute() {
        val samples = listOf(
            XiaomiSleepParser.RawActivitySample(0L, 112, 0, 0, null),
            XiaomiSleepParser.RawActivitySample(120_000L, 112, 0, 0, null)
        )
        val stages = XiaomiSleepParser.aggregateSleepStages(samples)
        assertEquals(2, stages.size)
        assertEquals(1, stages[0].durationMinutes)
        assertEquals(60_000L, stages[0].endTimestamp)
    }
}

