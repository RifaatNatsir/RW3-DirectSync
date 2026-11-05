package com.rw3.directsync.ble.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Date

class XiaomiWearNotificationTest {

    private val testAuthKey = "0123456789abcdef0123456789abcdef"
    private val engine = XiaomiWearProtocolEngine(testAuthKey)

    @Test
    fun buildNotificationSendCommand_serializesProperProtoFields() {
        val testDate = Date(1730800000000L) // Fixed timestamp for reproducibility
        val cmd = engine.buildNotificationSendCommand(
            id = 42,
            packageName = "com.whatsapp",
            appName = "WhatsApp",
            title = "Budi Hartono",
            body = "Halo, apakah proyek RW3-DirectSync sudah siap?",
            timestamp = testDate,
            isCall = false,
            key = "0|com.whatsapp|42|null|1000"
        )

        assertNotNull(cmd)
        assertTrue(cmd.size > 20)

        // Parse top-level command
        val topFields = engine.parseProtoFields(cmd)
        val type = topFields.firstOrNull { it.fieldNumber == 1 }?.intVal?.toInt()
        val subtype = topFields.firstOrNull { it.fieldNumber == 2 }?.intVal?.toInt()
        val notifBytes = topFields.firstOrNull { it.fieldNumber == 9 }?.bytesVal

        assertEquals(XiaomiWearProtocolEngine.CMD_TYPE_NOTIFICATION, type) // 7
        assertEquals(XiaomiWearProtocolEngine.CMD_SUBTYPE_NOTIFICATION_SEND, subtype) // 0
        assertNotNull(notifBytes)

        // Parse Notification: field 3 = Notification2
        val notifFields = engine.parseProtoFields(notifBytes!!)
        val notif2Bytes = notifFields.firstOrNull { it.fieldNumber == 3 }?.bytesVal
        assertNotNull(notif2Bytes)

        // Parse Notification2: field 1 = Notification3
        val notif2Fields = engine.parseProtoFields(notif2Bytes!!)
        val notif3Bytes = notif2Fields.firstOrNull { it.fieldNumber == 1 }?.bytesVal
        assertNotNull(notif3Bytes)

        // Parse Notification3 fields
        val notif3Fields = engine.parseProtoFields(notif3Bytes!!)
        val pkg = notif3Fields.firstOrNull { it.fieldNumber == 1 }?.bytesVal?.toString(Charsets.UTF_8)
        val appName = notif3Fields.firstOrNull { it.fieldNumber == 2 }?.bytesVal?.toString(Charsets.UTF_8)
        val title = notif3Fields.firstOrNull { it.fieldNumber == 3 }?.bytesVal?.toString(Charsets.UTF_8)
        val body = notif3Fields.firstOrNull { it.fieldNumber == 5 }?.bytesVal?.toString(Charsets.UTF_8)
        val id = notif3Fields.firstOrNull { it.fieldNumber == 7 }?.intVal?.toInt()

        assertEquals("com.whatsapp", pkg)
        assertEquals("WhatsApp", appName)
        assertEquals("Budi Hartono", title)
        assertEquals("Halo, apakah proyek RW3-DirectSync sudah siap?", body)
        assertEquals(42, id)
    }

    @Test
    fun buildNotificationSendCommand_incomingCall_setsIsCallFlag() {
        val cmd = engine.buildNotificationSendCommand(
            id = 101,
            packageName = "phone",
            appName = "Telepon",
            title = "Kantor",
            body = "+628123456789",
            isCall = true
        )

        val topFields = engine.parseProtoFields(cmd)
        val notifBytes = topFields.firstOrNull { it.fieldNumber == 9 }?.bytesVal!!
        val notifFields = engine.parseProtoFields(notifBytes)
        val notif2Bytes = notifFields.firstOrNull { it.fieldNumber == 3 }?.bytesVal!!
        val notif2Fields = engine.parseProtoFields(notif2Bytes)
        val notif3Bytes = notif2Fields.firstOrNull { it.fieldNumber == 1 }?.bytesVal!!
        val notif3Fields = engine.parseProtoFields(notif3Bytes)

        val isCall = notif3Fields.firstOrNull { it.fieldNumber == 8 }?.intVal?.toInt()
        val repliesAllowed = notif3Fields.firstOrNull { it.fieldNumber == 11 }?.intVal?.toInt()

        assertEquals(1, isCall)
        assertEquals(1, repliesAllowed)
    }

    @Test
    fun buildNotificationDismissCommand_serializesProperDismissProto() {
        val cmd = engine.buildNotificationDismissCommand(
            id = 999,
            packageName = "com.whatsapp",
            key = "key999"
        )

        val topFields = engine.parseProtoFields(cmd)
        val type = topFields.firstOrNull { it.fieldNumber == 1 }?.intVal?.toInt()
        val subtype = topFields.firstOrNull { it.fieldNumber == 2 }?.intVal?.toInt()
        val notifBytes = topFields.firstOrNull { it.fieldNumber == 9 }?.bytesVal

        assertEquals(XiaomiWearProtocolEngine.CMD_TYPE_NOTIFICATION, type) // 7
        assertEquals(XiaomiWearProtocolEngine.CMD_SUBTYPE_NOTIFICATION_DISMISS, subtype) // 1
        assertNotNull(notifBytes)

        // Notification: field 4 = NotificationDismiss
        val notifFields = engine.parseProtoFields(notifBytes!!)
        val dismissBytes = notifFields.firstOrNull { it.fieldNumber == 4 }?.bytesVal
        assertNotNull(dismissBytes)

        // NotificationDismiss: field 1 = NotificationId
        val dismissFields = engine.parseProtoFields(dismissBytes!!)
        val idBytes = dismissFields.firstOrNull { it.fieldNumber == 1 }?.bytesVal
        assertNotNull(idBytes)

        // NotificationId: field 1 = id, field 2 = package, field 4 = key
        val idFields = engine.parseProtoFields(idBytes!!)
        val id = idFields.firstOrNull { it.fieldNumber == 1 }?.intVal?.toInt()
        val pkg = idFields.firstOrNull { it.fieldNumber == 2 }?.bytesVal?.toString(Charsets.UTF_8)
        val key = idFields.firstOrNull { it.fieldNumber == 4 }?.bytesVal?.toString(Charsets.UTF_8)

        assertEquals(999, id)
        assertEquals("com.whatsapp", pkg)
        assertEquals("key999", key)
    }

    @Test
    fun decodeIncomingCommandProto_dismissAndCallEvents() {
        val dismissCmd = engine.buildNotificationDismissCommand(id = 888)
        val event = engine.decodeIncomingCommandProto(dismissCmd)

        assertTrue(event is XiaomiWearProtocolEngine.DecodedWatchEvent.NotificationDismissed)
        assertEquals(888, (event as XiaomiWearProtocolEngine.DecodedWatchEvent.NotificationDismissed).id)

        val rejectCmd = engine.buildCommandProto(
            XiaomiWearProtocolEngine.CMD_TYPE_NOTIFICATION,
            XiaomiWearProtocolEngine.CMD_SUBTYPE_CALL_REJECT
        )
        val rejectEvent = engine.decodeIncomingCommandProto(rejectCmd)
        assertEquals(XiaomiWearProtocolEngine.DecodedWatchEvent.CallRejectReceived, rejectEvent)
    }
}

