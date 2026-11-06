package com.rw3.directsync.ble.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

class XiaomiWearMusicProtocolTest {

    private val testAuthKey = "0123456789abcdef0123456789abcdef"
    private val engine = XiaomiWearProtocolEngine(testAuthKey)

    @Test
    fun buildMusicInfoCommand_serializesCorrectProtobufWireFields() {
        val cmd = engine.buildMusicInfoCommand(
            state = 1, // Playing
            volumePercent = 75,
            track = "Starboy",
            artist = "The Weeknd",
            positionSeconds = 42,
            durationSeconds = 230
        )

        assertNotNull(cmd)
        assertTrue(cmd.isNotEmpty())

        val topFields = engine.parseProtoFields(cmd)
        val type = topFields.firstOrNull { it.fieldNumber == 1 }?.intVal?.toInt()
        val subtype = topFields.firstOrNull { it.fieldNumber == 2 }?.intVal?.toInt()
        val musicBytes = topFields.firstOrNull { it.fieldNumber == 20 }?.bytesVal

        assertEquals(XiaomiWearProtocolEngine.CMD_TYPE_MUSIC, type) // 18
        assertEquals(XiaomiWearProtocolEngine.CMD_SUBTYPE_MUSIC_INFO, subtype) // 1
        assertNotNull(musicBytes)

        // Parse Music (field 20) -> MusicInfo (field 1)
        val musicFields = engine.parseProtoFields(musicBytes!!)
        val musicInfoBytes = musicFields.firstOrNull { it.fieldNumber == 1 }?.bytesVal
        assertNotNull(musicInfoBytes)

        val infoFields = engine.parseProtoFields(musicInfoBytes!!)
        val state = infoFields.firstOrNull { it.fieldNumber == 1 }?.intVal?.toInt()
        val volume = infoFields.firstOrNull { it.fieldNumber == 2 }?.intVal?.toInt()
        val track = infoFields.firstOrNull { it.fieldNumber == 4 }?.bytesVal?.toString(Charsets.UTF_8)
        val artist = infoFields.firstOrNull { it.fieldNumber == 5 }?.bytesVal?.toString(Charsets.UTF_8)
        val position = infoFields.firstOrNull { it.fieldNumber == 6 }?.intVal?.toInt()
        val duration = infoFields.firstOrNull { it.fieldNumber == 7 }?.intVal?.toInt()

        assertEquals(1, state)
        assertEquals(75, volume)
        assertEquals("Starboy", track)
        assertEquals("The Weeknd", artist)
        assertEquals(42, position)
        assertEquals(230, duration)
    }

    @Test
    fun decodeIncomingMediaKey_decodesPlaybackAndVolumeCommands() {
        // Construct incoming MediaKey packet from Redmi Watch 3: Next track (key=4)
        val keyStream = ByteArrayOutputStream().apply {
            write(0x08) // field 1 (key), wire type 0
            write(4)    // 4 = next track
            write(0x10) // field 2 (volume), wire type 0
            write(0)
        }.toByteArray()

        val musicStream = ByteArrayOutputStream().apply {
            write(0x12) // field 2 (mediaKey), wire type 2
            write(keyStream.size)
            write(keyStream)
        }.toByteArray()

        val rawCommand = engine.buildCommandProto(
            type = XiaomiWearProtocolEngine.CMD_TYPE_MUSIC,
            subtype = XiaomiWearProtocolEngine.CMD_SUBTYPE_MEDIA_KEY,
            nestedFieldNumber = 20,
            nestedBytes = musicStream
        )

        val event = engine.decodeIncomingCommandProto(rawCommand)
        assertTrue(event is XiaomiWearProtocolEngine.DecodedWatchEvent.MediaKeyReceived)

        val mediaKey = event as XiaomiWearProtocolEngine.DecodedWatchEvent.MediaKeyReceived
        assertEquals(4, mediaKey.key) // Next track
        assertEquals(0, mediaKey.volume)
    }

    @Test
    fun decodeIncomingMediaKey_volumeAdjustment() {
        // Construct incoming MediaKey packet: Volume Up (key=5, volume=100)
        val keyStream = ByteArrayOutputStream().apply {
            write(0x08) // field 1 (key), wire type 0
            write(5)    // 5 = volume
            write(0x10) // field 2 (volume), wire type 0
            write(100)  // 100 = volume up
        }.toByteArray()

        val musicStream = ByteArrayOutputStream().apply {
            write(0x12) // field 2 (mediaKey), wire type 2
            write(keyStream.size)
            write(keyStream)
        }.toByteArray()

        val rawCommand = engine.buildCommandProto(
            type = XiaomiWearProtocolEngine.CMD_TYPE_MUSIC,
            subtype = XiaomiWearProtocolEngine.CMD_SUBTYPE_MEDIA_KEY,
            nestedFieldNumber = 20,
            nestedBytes = musicStream
        )

        val event = engine.decodeIncomingCommandProto(rawCommand)
        assertTrue(event is XiaomiWearProtocolEngine.DecodedWatchEvent.MediaKeyReceived)

        val mediaKey = event as XiaomiWearProtocolEngine.DecodedWatchEvent.MediaKeyReceived
        assertEquals(5, mediaKey.key)
        assertEquals(100, mediaKey.volume)
    }

    @Test
    fun decodeIncomingMusicInfoRequest_returnsMusicInfoRequestedEvent() {
        val rawCommand = engine.buildCommandProto(
            type = XiaomiWearProtocolEngine.CMD_TYPE_MUSIC,
            subtype = XiaomiWearProtocolEngine.CMD_SUBTYPE_MUSIC_INFO
        )

        val event = engine.decodeIncomingCommandProto(rawCommand)
        assertTrue(event is XiaomiWearProtocolEngine.DecodedWatchEvent.MusicInfoRequested)
    }
}

