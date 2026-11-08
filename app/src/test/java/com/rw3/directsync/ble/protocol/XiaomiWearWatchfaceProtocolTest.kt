package com.rw3.directsync.ble.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

class XiaomiWearWatchfaceProtocolTest {

    private val testAuthKey = "0123456789abcdef0123456789abcdef"
    private val engine = XiaomiWearProtocolEngine(testAuthKey)

    @Test
    fun buildWatchfaceListQueryCommand_hasCorrectTypeAndSubtype() {
        val cmd = engine.buildWatchfaceListQueryCommand()
        assertNotNull(cmd)
        assertTrue(cmd.isNotEmpty())

        val fields = engine.parseProtoFields(cmd)
        val type = fields.firstOrNull { it.fieldNumber == 1 }?.intVal?.toInt()
        val subtype = fields.firstOrNull { it.fieldNumber == 2 }?.intVal?.toInt()

        assertEquals(XiaomiWearProtocolEngine.CMD_TYPE_WATCHFACE, type) // 4
        assertEquals(XiaomiWearProtocolEngine.CMD_SUBTYPE_WATCHFACE_LIST, subtype) // 0
    }

    @Test
    fun buildWatchfaceSetCommand_encodesWatchfaceId() {
        val testId = "com.xiaomi.album.dial"
        val cmd = engine.buildWatchfaceSetCommand(testId)
        assertNotNull(cmd)

        val fields = engine.parseProtoFields(cmd)
        val type = fields.firstOrNull { it.fieldNumber == 1 }?.intVal?.toInt()
        val subtype = fields.firstOrNull { it.fieldNumber == 2 }?.intVal?.toInt()
        val wfBytes = fields.firstOrNull { it.fieldNumber == 6 }?.bytesVal

        assertEquals(XiaomiWearProtocolEngine.CMD_TYPE_WATCHFACE, type) // 4
        assertEquals(XiaomiWearProtocolEngine.CMD_SUBTYPE_WATCHFACE_SET, subtype) // 1
        assertNotNull(wfBytes)

        val wfFields = engine.parseProtoFields(wfBytes!!)
        val idVal = wfFields.firstOrNull { it.fieldNumber == 2 }?.bytesVal?.toString(Charsets.UTF_8)
        assertEquals(testId, idVal)
    }

    @Test
    fun buildDataUploadRequestCommand_encodesWatchfaceTypeAndSize() {
        val fakeMd5 = ByteArray(16) { it.toByte() }
        val fakeSize = 123456
        val cmd = engine.buildDataUploadRequestCommand(fakeMd5, fakeSize)
        assertNotNull(cmd)

        val fields = engine.parseProtoFields(cmd)
        val type = fields.firstOrNull { it.fieldNumber == 1 }?.intVal?.toInt()
        val subtype = fields.firstOrNull { it.fieldNumber == 2 }?.intVal?.toInt()
        val upBytes = fields.firstOrNull { it.fieldNumber == 24 }?.bytesVal

        assertEquals(XiaomiWearProtocolEngine.CMD_TYPE_DATA_UPLOAD, type) // 22
        assertEquals(XiaomiWearProtocolEngine.CMD_SUBTYPE_DATA_UPLOAD_REQUEST, subtype) // 0
        assertNotNull(upBytes)

        val uFields = engine.parseProtoFields(upBytes!!)
        val reqBytes = uFields.firstOrNull { it.fieldNumber == 1 }?.bytesVal
        assertNotNull(reqBytes)

        val rFields = engine.parseProtoFields(reqBytes!!)
        val uploadType = rFields.firstOrNull { it.fieldNumber == 1 }?.intVal?.toInt()
        val md5Val = rFields.firstOrNull { it.fieldNumber == 2 }?.bytesVal
        val sizeVal = rFields.firstOrNull { it.fieldNumber == 3 }?.intVal?.toInt()

        assertEquals(XiaomiWearProtocolEngine.DATA_UPLOAD_TYPE_WATCHFACE, uploadType) // 16
        assertTrue(fakeMd5.contentEquals(md5Val))
        assertEquals(fakeSize, sizeVal)
    }

    @Test
    fun decodeWatchfaceList_parsesMultipleWatchfacesAndActiveStatus() {
        // Construct Watchface 1: id="200000001", name="Classic Analog", active=true, canDelete=false
        val w1Stream = ByteArrayOutputStream().apply {
            write(byteArrayOf(0x0a, 0x09)) // field 1 (id), len 9
            write("200000001".toByteArray(Charsets.UTF_8))
            write(byteArrayOf(0x12, 0x0e)) // field 2 (name), len 14
            write("Classic Analog".toByteArray(Charsets.UTF_8))
            write(byteArrayOf(0x18, 0x01)) // field 3 (active), varint 1
            write(byteArrayOf(0x20, 0x00)) // field 4 (canDelete), varint 0
        }

        // Construct Watchface 2: id="com.xiaomi.album", name="Album Watch Face", active=false, canDelete=true
        val w2Stream = ByteArrayOutputStream().apply {
            write(byteArrayOf(0x0a, 0x10)) // field 1 (id), len 16
            write("com.xiaomi.album".toByteArray(Charsets.UTF_8))
            write(byteArrayOf(0x12, 0x10)) // field 2 (name), len 16
            write("Album Watch Face".toByteArray(Charsets.UTF_8))
            write(byteArrayOf(0x18, 0x00)) // field 3 (active), varint 0
            write(byteArrayOf(0x20, 0x01)) // field 4 (canDelete), varint 1
        }

        // WatchfaceList (field 1 of Watchface)
        val listStream = ByteArrayOutputStream().apply {
            write(0x0a) // field 1 (WatchfaceInfo), wire type 2
            write(w1Stream.size())
            write(w1Stream.toByteArray())

            write(0x0a) // field 1 (WatchfaceInfo), wire type 2
            write(w2Stream.size())
            write(w2Stream.toByteArray())
        }

        // Watchface (field 6 of Command)
        val wfStream = ByteArrayOutputStream().apply {
            write(0x0a) // field 1 (WatchfaceList), wire type 2
            write(listStream.size())
            write(listStream.toByteArray())
        }

        val rawCmd = engine.buildCommandProto(
            type = XiaomiWearProtocolEngine.CMD_TYPE_WATCHFACE,
            subtype = XiaomiWearProtocolEngine.CMD_SUBTYPE_WATCHFACE_LIST,
            nestedFieldNumber = 6,
            nestedBytes = wfStream.toByteArray()
        )

        val event = engine.decodeIncomingCommandProto(rawCmd)
        assertTrue(event is XiaomiWearProtocolEngine.DecodedWatchEvent.WatchfaceListReceived)

        val listEvent = event as XiaomiWearProtocolEngine.DecodedWatchEvent.WatchfaceListReceived
        assertEquals(2, listEvent.watchfaces.size)

        val wf1 = listEvent.watchfaces[0]
        assertEquals("200000001", wf1.id)
        assertEquals("Classic Analog", wf1.name)
        assertTrue(wf1.isActive)
        assertFalse(wf1.canDelete)

        val wf2 = listEvent.watchfaces[1]
        assertEquals("com.xiaomi.album", wf2.id)
        assertEquals("Album Watch Face", wf2.name)
        assertFalse(wf2.isActive)
        assertTrue(wf2.canDelete)
    }

    @Test
    fun decodeDataUploadAck_parsesResumePosAndChunkSize() {
        val fakeMd5 = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16)
        val ackStream = ByteArrayOutputStream().apply {
            write(byteArrayOf(0x0a, 0x10)) // field 1 (md5), len 16
            write(fakeMd5)
            write(byteArrayOf(0x20, 0x00)) // field 4 (resumePosition), varint 0
            write(byteArrayOf(0x28, 0x80.toByte(), 0x20)) // field 5 (chunkSize), varint 4096
        }

        val uploadStream = ByteArrayOutputStream().apply {
            write(0x12) // field 2 (dataUploadAck), wire type 2
            write(ackStream.size())
            write(ackStream.toByteArray())
        }

        val rawCmd = engine.buildCommandProto(
            type = XiaomiWearProtocolEngine.CMD_TYPE_DATA_UPLOAD,
            subtype = 0,
            nestedFieldNumber = 24,
            nestedBytes = uploadStream.toByteArray()
        )

        val event = engine.decodeIncomingCommandProto(rawCmd)
        assertTrue(event is XiaomiWearProtocolEngine.DecodedWatchEvent.DataUploadAckReceived)

        val ackEvent = event as XiaomiWearProtocolEngine.DecodedWatchEvent.DataUploadAckReceived
        assertTrue(fakeMd5.contentEquals(ackEvent.md5sum))
        assertEquals(0, ackEvent.resumePosition)
        assertEquals(4096, ackEvent.chunkSize)
    }
}

