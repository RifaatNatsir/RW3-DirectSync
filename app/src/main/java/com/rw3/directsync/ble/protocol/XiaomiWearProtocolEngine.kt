package com.rw3.directsync.ble.protocol

import android.os.Build
import com.rw3.directsync.crypto.XiaomiAuthCrypto
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Native implementation of the Xiaomi Wear Protobuf + SPP/SAR Framing & Cryptography
 * used by Redmi Watch 3 (`lchz.watch.m65sa`), modeled directly after Gadgetbridge's
 * `XiaomiAuthService`, `XiaomiSppPacketV1`, `XiaomiSppPacketV2`, and `XiaomiHealthService`.
 */
class XiaomiWearProtocolEngine(authKeyHex: String) {

    companion object {
        // Command Types & Subtypes (from xiaomi.proto & wear_fitness.proto)
        const val CMD_TYPE_AUTH = 1
        const val CMD_SUBTYPE_SEND_USERID = 5
        const val CMD_SUBTYPE_NONCE = 26
        const val CMD_SUBTYPE_AUTH = 27

        const val CMD_TYPE_SYSTEM = 2
        const val CMD_SUBTYPE_BATTERY = 1
        const val CMD_SUBTYPE_DEVICE_INFO = 2

        const val CMD_TYPE_NOTIFICATION = 7
        const val CMD_SUBTYPE_NOTIFICATION_SEND = 0
        const val CMD_SUBTYPE_NOTIFICATION_DISMISS = 1
        const val CMD_SUBTYPE_CALL_REJECT = 2
        const val CMD_SUBTYPE_CALL_IGNORE = 5
        const val CMD_SUBTYPE_OPEN_ON_PHONE = 8

        const val CMD_TYPE_HEALTH = 8
        const val CMD_SUBTYPE_ACTIVITY_FETCH_TODAY = 1
        const val CMD_SUBTYPE_ACTIVITY_FETCH_PAST = 2
        const val CMD_SUBTYPE_ACTIVITY_FETCH_REQUEST = 3
        const val CMD_SUBTYPE_ACTIVITY_FETCH_ACK = 5
        const val CMD_SUBTYPE_REALTIME_STATS_START = 45
        const val CMD_SUBTYPE_REALTIME_STATS_STOP = 46
        const val CMD_SUBTYPE_REALTIME_STATS_EVENT = 47
        const val CMD_SUBTYPE_WEAR_SPORT_DATA_V2A = 50

        // SPP V1 Framing Constants
        val SPP_V1_PREAMBLE = byteArrayOf(0xBA.toByte(), 0xDC.toByte(), 0xFE.toByte())
        const val SPP_V1_EPILOGUE: Byte = 0xEF.toByte()

        // SPP V2 Framing Constants
        val SPP_V2_PREAMBLE = byteArrayOf(0xA5.toByte(), 0xA5.toByte())
        const val SPP_V2_TYPE_NACK = 0
        const val SPP_V2_TYPE_ACK = 1
        const val SPP_V2_TYPE_SESSION_CONFIG = 2
        const val SPP_V2_TYPE_DATA = 3
    }

    private val secretKey: ByteArray = XiaomiAuthCrypto.parseAuthKeyHex(authKeyHex)
    val phoneNonce: ByteArray = ByteArray(16).also { SecureRandom().nextBytes(it) }

    val decryptionKey = ByteArray(16)
    val encryptionKey = ByteArray(16)
    val decryptionNonce = ByteArray(4)
    val encryptionNonce = ByteArray(4)

    @Volatile
    var isEncryptionInitialized: Boolean = false
        private set

    private var frameCounterV1: Int = 0
    private var encryptionCounterV1: Int = 0
    private var sequenceCounterV2: Int = 0
    private var bleEncryptedIndex: Int = 1 // 0 is used by auth step 3

    fun resetCounters() {
        isEncryptionInitialized = false
        frameCounterV1 = 0
        encryptionCounterV1 = 0
        sequenceCounterV2 = 0
        bleEncryptedIndex = 1
        SecureRandom().nextBytes(phoneNonce)
    }

    // =========================================================================
    // 1. Minimal Protobuf Wire Format Encoder / Decoder (No External Dependency)
    // =========================================================================

    private fun ByteArrayOutputStream.writeVarint(value: Int) {
        var v = value
        while ((v and 0x7F.inv()) != 0) {
            write((v and 0x7F) or 0x80)
            v = v ushr 7
        }
        write(v and 0x7F)
    }

    private fun ByteArrayOutputStream.writeTag(fieldNumber: Int, wireType: Int) {
        writeVarint((fieldNumber shl 3) or wireType)
    }

    private fun ByteArrayOutputStream.writeUint32Field(fieldNumber: Int, value: Int) {
        writeTag(fieldNumber, 0) // WireType 0 = Varint
        writeVarint(value)
    }

    private fun ByteArrayOutputStream.writeFixed32FloatField(fieldNumber: Int, value: Float) {
        writeTag(fieldNumber, 5) // WireType 5 = 32-bit
        val buf = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putFloat(value)
        write(buf.array())
    }

    private fun ByteArrayOutputStream.writeBytesField(fieldNumber: Int, bytes: ByteArray) {
        writeTag(fieldNumber, 2) // WireType 2 = Length-delimited
        writeVarint(bytes.size)
        write(bytes)
    }

    private fun ByteArrayOutputStream.writeStringField(fieldNumber: Int, text: String) {
        writeBytesField(fieldNumber, text.toByteArray(Charsets.UTF_8))
    }

    private fun ByteArrayOutputStream.writeBoolField(fieldNumber: Int, value: Boolean) {
        writeTag(fieldNumber, 0) // WireType 0 = Varint
        write(if (value) 1 else 0)
    }

    /**
     * Builds Protobuf `Command`:
     *   required uint32 type = 1;
     *   optional uint32 subtype = 2;
     *   optional <nested> = nestedFieldNumber;
     */
    fun buildCommandProto(
        type: Int,
        subtype: Int,
        nestedFieldNumber: Int? = null,
        nestedBytes: ByteArray? = null
    ): ByteArray {
        val out = ByteArrayOutputStream()
        out.writeUint32Field(1, type)
        out.writeUint32Field(2, subtype)
        if (nestedFieldNumber != null && nestedBytes != null) {
            out.writeBytesField(nestedFieldNumber, nestedBytes)
        }
        return out.toByteArray()
    }

    /**
     * Builds Auth Step 1 (`type=1, subtype=26`) containing `Account.appVerify (field 30) -> nonce (field 1)`.
     */
    fun buildAuthStep1NonceCommand(): ByteArray {
        val appVerify = ByteArrayOutputStream().apply {
            writeBytesField(1, phoneNonce)
        }.toByteArray()

        val account = ByteArrayOutputStream().apply {
            writeBytesField(30, appVerify)
        }.toByteArray()

        return buildCommandProto(CMD_TYPE_AUTH, CMD_SUBTYPE_NONCE, 3, account)
    }

    /**
     * Builds Realtime Heart Rate & Steps Streaming Command:
     * `Command(type = 8, subtype = 45 [START] or 46 [STOP])`.
     */
    fun buildRealtimeStatsCommand(enable: Boolean): ByteArray {
        val subtype = if (enable) CMD_SUBTYPE_REALTIME_STATS_START else CMD_SUBTYPE_REALTIME_STATS_STOP
        return buildCommandProto(CMD_TYPE_HEALTH, subtype)
    }

    /**
     * Builds Battery Query Command: `Command(type = 2, subtype = 1)`
     */
    fun buildBatteryQueryCommand(): ByteArray {
        return buildCommandProto(CMD_TYPE_SYSTEM, CMD_SUBTYPE_BATTERY)
    }

    /**
     * Builds Activity / Sleep Fetch Today Command:
     * `Command(type = 8, subtype = 1, health = Health(activitySyncRequestToday = { unknown1 = 0 }))`
     */
    fun buildFetchActivityTodayCommand(requestMode: Int = 0): ByteArray {
        require(requestMode in 0..1)
        val syncReqToday = ByteArrayOutputStream().apply {
            writeUint32Field(1, requestMode)
        }.toByteArray()
        val health = ByteArrayOutputStream().apply {
            writeBytesField(5, syncReqToday)
        }.toByteArray()
        return buildCommandProto(CMD_TYPE_HEALTH, CMD_SUBTYPE_ACTIVITY_FETCH_TODAY, 10, health)
    }

    /**
     * Builds Activity / Sleep Fetch Past Command: `Command(type = 8, subtype = 2)`
     */
    fun buildFetchActivityPastCommand(): ByteArray {
        return buildCommandProto(CMD_TYPE_HEALTH, CMD_SUBTYPE_ACTIVITY_FETCH_PAST)
    }

    /**
     * Builds Activity / Sleep File Request Command: `Command(type = 8, subtype = 3, health = Health(activityRequestFileIds = fileId7Bytes))`
     */
    fun buildRequestActivityFileCommand(fileId7Bytes: ByteArray): ByteArray {
        val health = ByteArrayOutputStream().apply {
            writeBytesField(2, fileId7Bytes)
        }.toByteArray()
        return buildCommandProto(CMD_TYPE_HEALTH, CMD_SUBTYPE_ACTIVITY_FETCH_REQUEST, 10, health)
    }

    /**
     * Builds Smart Notification Command for Redmi Watch 3:
     * `Command(type = 7, subtype = 0, notification = Notification(notification2 = Notification2(notification3 = Notification3(...))))`
     */
    fun buildNotificationSendCommand(
        id: Int,
        packageName: String,
        appName: String,
        title: String,
        body: String,
        timestamp: Date = Date(),
        isCall: Boolean = false,
        key: String? = null
    ): ByteArray {
        val sdf = SimpleDateFormat("yyyyMMdd'T'HHmmss", Locale.ROOT)
        val tsStr = sdf.format(timestamp)

        val notif3 = ByteArrayOutputStream().apply {
            writeStringField(1, packageName)
            writeStringField(2, if (appName.isNotBlank()) appName else "App")
            writeStringField(3, title)
            writeStringField(4, "")
            writeStringField(5, body)
            writeStringField(6, tsStr)
            writeUint32Field(7, id)
            if (isCall) {
                writeBoolField(8, true)
                writeBoolField(11, true)
            }
            if (!key.isNullOrEmpty()) {
                writeStringField(12, key)
                writeBoolField(13, true)
            }
        }.toByteArray()

        val notif2 = ByteArrayOutputStream().apply {
            writeBytesField(1, notif3)
        }.toByteArray()

        val notif = ByteArrayOutputStream().apply {
            writeBytesField(3, notif2)
        }.toByteArray()

        return buildCommandProto(CMD_TYPE_NOTIFICATION, CMD_SUBTYPE_NOTIFICATION_SEND, 9, notif)
    }

    /**
     * Builds Notification Dismiss Command for Redmi Watch 3:
     * `Command(type = 7, subtype = 1, notification = Notification(notificationDismiss = NotificationDismiss(notificationId = ...)))`
     */
    fun buildNotificationDismissCommand(
        id: Int,
        packageName: String = "",
        key: String? = null
    ): ByteArray {
        val notifId = ByteArrayOutputStream().apply {
            writeUint32Field(1, id)
            if (packageName.isNotEmpty()) {
                writeStringField(2, packageName)
            }
            if (!key.isNullOrEmpty()) {
                writeStringField(4, key)
            }
        }.toByteArray()

        val notifDismiss = ByteArrayOutputStream().apply {
            writeBytesField(1, notifId)
        }.toByteArray()

        val notif = ByteArrayOutputStream().apply {
            writeBytesField(4, notifDismiss)
        }.toByteArray()

        return buildCommandProto(CMD_TYPE_NOTIFICATION, CMD_SUBTYPE_NOTIFICATION_DISMISS, 9, notif)
    }

    // =========================================================================
    // 2. Protobuf Wire Parser (Extracts Auth Nonce, Realtime HR, Activity File IDs)
    // =========================================================================

    data class ProtoField(val fieldNumber: Int, val wireType: Int, val intVal: Long = 0, val bytesVal: ByteArray = ByteArray(0))

    fun parseProtoFields(data: ByteArray): List<ProtoField> {
        val result = mutableListOf<ProtoField>()
        var offset = 0

        fun readVarint(): Long {
            var value = 0L
            var shift = 0
            while (offset < data.size) {
                val b = data[offset++].toInt() and 0xFF
                value = value or ((b and 0x7F).toLong() shl shift)
                if ((b and 0x80) == 0) break
                shift += 7
                if (shift >= 64) break
            }
            return value
        }

        try {
            while (offset < data.size) {
                val tag = readVarint().toInt()
                if (tag == 0) break
                val fieldNumber = tag ushr 3
                val wireType = tag and 0x07

                when (wireType) {
                    0 -> { // Varint
                        val v = readVarint()
                        result.add(ProtoField(fieldNumber, wireType, intVal = v))
                    }
                    1 -> { // 64-bit
                        if (offset + 8 > data.size) break
                        offset += 8
                    }
                    2 -> { // Length-delimited
                        val len = readVarint().toInt()
                        if (len < 0 || offset + len > data.size) break
                        val sub = data.copyOfRange(offset, offset + len)
                        offset += len
                        result.add(ProtoField(fieldNumber, wireType, bytesVal = sub))
                    }
                    5 -> { // 32-bit
                        if (offset + 4 > data.size) break
                        offset += 4
                    }
                    else -> break
                }
            }
        } catch (_: Exception) {
        }
        return result
    }

    sealed class DecodedWatchEvent {
        data class WatchNonceReceived(val step3ResponseCommand: ByteArray) : DecodedWatchEvent()
        data object AuthSucceeded : DecodedWatchEvent()
        data class RealtimeHeartRate(val bpm: Int, val steps: Int, val calories: Int) : DecodedWatchEvent()
        data class ActivityFileIdsReceived(val subtype: Int, val fileIds: List<ByteArray>) : DecodedWatchEvent()
        data class BatteryReport(val levelPercent: Int) : DecodedWatchEvent()
        data class NotificationDismissed(val id: Int) : DecodedWatchEvent()
        data object CallRejectReceived : DecodedWatchEvent()
        data object CallIgnoreReceived : DecodedWatchEvent()
        data class RawCommand(val type: Int, val subtype: Int) : DecodedWatchEvent()
    }

    fun decodeIncomingCommandProto(commandBytes: ByteArray): DecodedWatchEvent? {
        val topFields = parseProtoFields(commandBytes)
        val type = topFields.firstOrNull { it.fieldNumber == 1 && it.wireType == 0 }?.intVal?.toInt() ?: return null
        val subtype = topFields.firstOrNull { it.fieldNumber == 2 && it.wireType == 0 }?.intVal?.toInt() ?: 0

        when (type) {
            CMD_TYPE_AUTH -> {
                val authBytes = topFields.firstOrNull { it.fieldNumber == 3 && it.wireType == 2 }?.bytesVal
                val authFields = if (authBytes != null) parseProtoFields(authBytes) else emptyList()

                if (subtype == CMD_SUBTYPE_NONCE) {
                    val deviceVerifyBytes = authFields.firstOrNull { it.fieldNumber == 31 && it.wireType == 2 }?.bytesVal
                        ?: return null
                    val dvFields = parseProtoFields(deviceVerifyBytes)
                    val watchNonce = dvFields.firstOrNull { it.fieldNumber == 1 && it.wireType == 2 }?.bytesVal ?: return null
                    val watchHmac = dvFields.firstOrNull { it.fieldNumber == 2 && it.wireType == 2 }?.bytesVal ?: return null

                    val step3Cmd = handleWatchNonceAndBuildStep3(watchNonce, watchHmac) ?: return null
                    return DecodedWatchEvent.WatchNonceReceived(step3Cmd)
                } else if (subtype == CMD_SUBTYPE_AUTH || subtype == CMD_SUBTYPE_SEND_USERID) {
                    isEncryptionInitialized = (subtype == CMD_SUBTYPE_AUTH)
                    return DecodedWatchEvent.AuthSucceeded
                }
            }

            CMD_TYPE_SYSTEM -> {
                if (subtype == CMD_SUBTYPE_BATTERY) {
                    val sysBytes = topFields.firstOrNull { it.fieldNumber == 4 && it.wireType == 2 }?.bytesVal
                    if (sysBytes != null) {
                        val sysFields = parseProtoFields(sysBytes)
                        // Path A: sys -> power (field 2) -> battery (field 1) -> level (field 1)
                        val powerBytes = sysFields.firstOrNull { it.fieldNumber == 2 && it.wireType == 2 }?.bytesVal
                        if (powerBytes != null) {
                            val pFields = parseProtoFields(powerBytes)
                            val battBytes = pFields.firstOrNull { it.fieldNumber == 1 && it.wireType == 2 }?.bytesVal
                            if (battBytes != null) {
                                val bFields = parseProtoFields(battBytes)
                                val level = bFields.firstOrNull { it.fieldNumber == 1 }?.intVal?.toInt() ?: 0
                                if (level in 1..100) return DecodedWatchEvent.BatteryReport(level)
                            }
                            val directLevel = pFields.firstOrNull { it.fieldNumber == 1 && it.wireType == 0 }?.intVal?.toInt()
                            if (directLevel != null && directLevel in 1..100) {
                                return DecodedWatchEvent.BatteryReport(directLevel)
                            }
                        }
                        // Path B: sys -> battery (field 1)
                        val directBatt = sysFields.firstOrNull { it.fieldNumber == 1 && it.wireType == 0 }?.intVal?.toInt()
                        if (directBatt != null && directBatt in 1..100) {
                            return DecodedWatchEvent.BatteryReport(directBatt)
                        }
                    }
                    // Path C: check topFields for direct level
                    for (f in topFields) {
                        if (f.wireType == 0 && f.intVal in 1..100 && f.fieldNumber != 1 && f.fieldNumber != 2) {
                            return DecodedWatchEvent.BatteryReport(f.intVal.toInt())
                        }
                    }
                }
            }

            CMD_TYPE_HEALTH -> {
                val healthBytes = topFields.firstOrNull { it.fieldNumber == 10 && it.wireType == 2 }?.bytesVal
                    ?: return DecodedWatchEvent.RawCommand(type, subtype)
                val hFields = parseProtoFields(healthBytes)

                when (subtype) {
                    CMD_SUBTYPE_REALTIME_STATS_EVENT -> {
                        val rtBytes = hFields.firstOrNull { it.fieldNumber == 39 && it.wireType == 2 }?.bytesVal ?: return null
                        val rtFields = parseProtoFields(rtBytes)
                        val steps = rtFields.firstOrNull { it.fieldNumber == 1 }?.intVal?.toInt() ?: 0
                        val calories = rtFields.firstOrNull { it.fieldNumber == 2 }?.intVal?.toInt() ?: 0
                        val hr = rtFields.firstOrNull { it.fieldNumber == 4 }?.intVal?.toInt() ?: 0
                        return DecodedWatchEvent.RealtimeHeartRate(bpm = hr, steps = steps, calories = calories)
                    }
                    CMD_SUBTYPE_WEAR_SPORT_DATA_V2A -> {
                        val wsBytes = hFields.firstOrNull { it.fieldNumber == 42 && it.wireType == 2 }?.bytesVal ?: return null
                        val wsFields = parseProtoFields(wsBytes)
                        val hr = wsFields.firstOrNull { it.fieldNumber == 1 }?.intVal?.toInt() ?: 0
                        val calories = wsFields.firstOrNull { it.fieldNumber == 2 }?.intVal?.toInt() ?: 0
                        val steps = wsFields.firstOrNull { it.fieldNumber == 3 }?.intVal?.toInt() ?: 0
                        return DecodedWatchEvent.RealtimeHeartRate(bpm = hr, steps = steps, calories = calories)
                    }
                    CMD_SUBTYPE_ACTIVITY_FETCH_TODAY, CMD_SUBTYPE_ACTIVITY_FETCH_PAST -> {
                        val rawIds = hFields.firstOrNull { it.fieldNumber == 2 && it.wireType == 2 }?.bytesVal ?: ByteArray(0)
                        val list = mutableListOf<ByteArray>()
                        var idx = 0
                        while (idx + 7 <= rawIds.size) {
                            list.add(rawIds.copyOfRange(idx, idx + 7))
                            idx += 7
                        }
                        return DecodedWatchEvent.ActivityFileIdsReceived(subtype, list)
                    }
                }
            }

            CMD_TYPE_NOTIFICATION -> {
                when (subtype) {
                    CMD_SUBTYPE_NOTIFICATION_DISMISS -> {
                        val notifBytes = topFields.firstOrNull { it.fieldNumber == 9 && it.wireType == 2 }?.bytesVal
                        if (notifBytes != null) {
                            val nFields = parseProtoFields(notifBytes)
                            val dismissBytes = nFields.firstOrNull { it.fieldNumber == 4 && it.wireType == 2 }?.bytesVal
                            if (dismissBytes != null) {
                                val dFields = parseProtoFields(dismissBytes)
                                val idBytes = dFields.firstOrNull { it.fieldNumber == 1 && it.wireType == 2 }?.bytesVal
                                if (idBytes != null) {
                                    val iFields = parseProtoFields(idBytes)
                                    val notifId = iFields.firstOrNull { it.fieldNumber == 1 && it.wireType == 0 }?.intVal?.toInt() ?: 0
                                    return DecodedWatchEvent.NotificationDismissed(notifId)
                                }
                            }
                        }
                        return DecodedWatchEvent.NotificationDismissed(0)
                    }
                    CMD_SUBTYPE_CALL_REJECT -> return DecodedWatchEvent.CallRejectReceived
                    CMD_SUBTYPE_CALL_IGNORE -> return DecodedWatchEvent.CallIgnoreReceived
                    else -> return DecodedWatchEvent.RawCommand(type, subtype)
                }
            }
        }

        return DecodedWatchEvent.RawCommand(type, subtype)
    }

    // =========================================================================
    // 3. Xiaomi Wear Cryptography (HKDF-HMAC-SHA256 + AES-CCM + AES-CTR)
    // =========================================================================

    private fun handleWatchNonceAndBuildStep3(watchNonce: ByteArray, watchHmac: ByteArray): ByteArray? {
        val step2Hmac = computeAuthStep3Hmac(secretKey, phoneNonce, watchNonce)
        System.arraycopy(step2Hmac, 0, decryptionKey, 0, 16)
        System.arraycopy(step2Hmac, 16, encryptionKey, 0, 16)
        System.arraycopy(step2Hmac, 32, decryptionNonce, 0, 4)
        System.arraycopy(step2Hmac, 36, encryptionNonce, 0, 4)

        val confirmInput = watchNonce + phoneNonce
        val expectedWatchHmac = hmacSha256(decryptionKey, confirmInput)
        if (!expectedWatchHmac.contentEquals(watchHmac)) {
            return null
        }

        // Build CompanionDevice proto:
        //   required uint32 unknown1 = 1 (0)
        //   required float phoneApiLevel = 2
        //   required string phoneName = 3
        //   required uint32 unknown3 = 4 (224)
        //   required string region = 5 ("ID")
        val companionDevice = ByteArrayOutputStream().apply {
            writeUint32Field(1, 0)
            writeFixed32FloatField(2, Build.VERSION.SDK_INT.toFloat())
            writeStringField(3, Build.MODEL.take(24))
            writeUint32Field(4, 224)
            val region = Locale.getDefault().country.takeIf { it.length == 2 }?.uppercase(Locale.ROOT) ?: "ID"
            writeStringField(5, region)
        }.toByteArray()

        val encryptedNonces = hmacSha256(encryptionKey, phoneNonce + watchNonce)
        val encryptedDeviceInfo = encryptAesCcm(companionDevice, counter = 0)

        val appConfirm = ByteArrayOutputStream().apply {
            writeBytesField(1, encryptedNonces)
            writeBytesField(2, encryptedDeviceInfo)
        }.toByteArray()

        val account = ByteArrayOutputStream().apply {
            writeBytesField(32, appConfirm)
        }.toByteArray()

        return buildCommandProto(CMD_TYPE_AUTH, CMD_SUBTYPE_AUTH, 3, account)
    }

    fun computeAuthStep3Hmac(secretKey: ByteArray, phoneNonce: ByteArray, watchNonce: ByteArray): ByteArray {
        val miwearAuthBytes = "miwear-auth".toByteArray(Charsets.UTF_8)
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(phoneNonce + watchNonce, "HmacSHA256"))
        val prk = mac.doFinal(secretKey)

        mac.init(SecretKeySpec(prk, "HmacSHA256"))
        val output = ByteArray(64)
        var tmp = ByteArray(0)
        var b: Byte = 1
        var i = 0
        while (i < output.size) {
            mac.update(tmp)
            mac.update(miwearAuthBytes)
            mac.update(b)
            tmp = mac.doFinal()
            var j = 0
            while (j < tmp.size && i < output.size) {
                output[i++] = tmp[j++]
            }
            b++
        }
        return output
    }

    private fun hmacSha256(key: ByteArray, input: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(input)
    }

    /**
     * Pure Kotlin AES-128-CCM implementation (RFC 3610) with 4-byte (32-bit) MAC tag and 12-byte Nonce
     * (L = 15 - 12 = 3, M = 4 -> Flags = 0x0A for MIC, 0x01 for CTR).
     */
    fun encryptAesCcm(plaintext: ByteArray, counter: Int): ByteArray {
        val nonce12 = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN)
            .put(encryptionNonce)
            .putInt(0)
            .putInt(counter)
            .array()
        return aes128CcmEncrypt(encryptionKey, nonce12, plaintext)
    }

    fun decryptAesCcm(ciphertextWithTag: ByteArray): ByteArray {
        val nonce12 = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN)
            .put(decryptionNonce)
            .putInt(0)
            .putInt(0)
            .array()
        return aes128CcmDecrypt(decryptionKey, nonce12, ciphertextWithTag)
    }

    private fun aesBlock(key: ByteArray, block16: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/ECB/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"))
        return cipher.doFinal(block16)
    }

    private fun aes128CcmEncrypt(key: ByteArray, nonce12: ByteArray, payload: ByteArray): ByteArray {
        // RFC 3610: M=4 ((4-2)/2 = 1 -> bits 3..5 = 001), L=3 (L-1 = 2 -> bits 0..2 = 010) => B0 flags = 0x0A
        val b0 = ByteArray(16)
        b0[0] = 0x0A
        System.arraycopy(nonce12, 0, b0, 1, 12)
        val len = payload.size
        b0[13] = ((len ushr 16) and 0xFF).toByte()
        b0[14] = ((len ushr 8) and 0xFF).toByte()
        b0[15] = (len and 0xFF).toByte()

        var x = aesBlock(key, b0)
        var offset = 0
        while (offset < len) {
            val block = ByteArray(16)
            val copyLen = minOf(16, len - offset)
            System.arraycopy(payload, offset, block, 0, copyLen)
            for (k in 0 until 16) {
                x[k] = (x[k].toInt() xor block[k].toInt()).toByte()
            }
            x = aesBlock(key, x)
            offset += 16
        }
        val tagT = x.copyOfRange(0, 4)

        // Counter blocks A_i: flags = L-1 = 0x02
        fun counterBlock(i: Int): ByteArray {
            val a = ByteArray(16)
            a[0] = 0x02
            System.arraycopy(nonce12, 0, a, 1, 12)
            a[13] = ((i ushr 16) and 0xFF).toByte()
            a[14] = ((i ushr 8) and 0xFF).toByte()
            a[15] = (i and 0xFF).toByte()
            return aesBlock(key, a)
        }

        val s0 = counterBlock(0)
        val encryptedTag = ByteArray(4) { idx -> (tagT[idx].toInt() xor s0[idx].toInt()).toByte() }

        val out = ByteArray(len + 4)
        var blockIdx = 1
        var pos = 0
        while (pos < len) {
            val si = counterBlock(blockIdx++)
            val n = minOf(16, len - pos)
            for (j in 0 until n) {
                out[pos + j] = (payload[pos + j].toInt() xor si[j].toInt()).toByte()
            }
            pos += n
        }
        System.arraycopy(encryptedTag, 0, out, len, 4)
        return out
    }

    private fun aes128CcmDecrypt(key: ByteArray, nonce12: ByteArray, encryptedWithTag: ByteArray): ByteArray {
        if (encryptedWithTag.size < 4) return encryptedWithTag
        val cipherLen = encryptedWithTag.size - 4

        fun counterBlock(i: Int): ByteArray {
            val a = ByteArray(16)
            a[0] = 0x02
            System.arraycopy(nonce12, 0, a, 1, 12)
            a[13] = ((i ushr 16) and 0xFF).toByte()
            a[14] = ((i ushr 8) and 0xFF).toByte()
            a[15] = (i and 0xFF).toByte()
            return aesBlock(key, a)
        }

        val plain = ByteArray(cipherLen)
        var blockIdx = 1
        var pos = 0
        while (pos < cipherLen) {
            val si = counterBlock(blockIdx++)
            val n = minOf(16, cipherLen - pos)
            for (j in 0 until n) {
                plain[pos + j] = (encryptedWithTag[pos + j].toInt() xor si[j].toInt()).toByte()
            }
            pos += n
        }
        return plain
    }

    fun encryptV2Ctr(plain: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/CTR/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(encryptionKey, "AES"), IvParameterSpec(encryptionKey))
        return cipher.doFinal(plain)
    }

    fun decryptV2Ctr(cipherBytes: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/CTR/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(decryptionKey, "AES"), IvParameterSpec(decryptionKey))
        return cipher.doFinal(cipherBytes)
    }

    // =========================================================================
    // 4. SPP V1, SPP V2, and BLE V1 SAR Packet Framing
    // =========================================================================

    /**
     * SPP V1 Version Request packet (`BA DC FE 00 C0 03 00 00 00 00 EF`).
     */
    fun buildSppV1VersionRequest(): ByteArray {
        return byteArrayOf(
            0xBA.toByte(), 0xDC.toByte(), 0xFE.toByte(),
            0x00, // Channel 0 = Version
            0xC0.toByte(), // flag=true (0x80) | needsResponse=true (0x40)
            0x03, 0x00, // length = 3
            0x00, // opCode = READ
            0x00, // frameSerial = 0
            0x00, // dataType = PLAIN
            0xEF.toByte()
        )
    }

    fun encodeSppV1Command(commandProto: ByteArray, isAuthPacket: Boolean): ByteArray {
        val serial = frameCounterV1++ and 0xFF
        val dataType: Int
        val payloadToFrame: ByteArray

        if (isAuthPacket) {
            dataType = 2 // DATA_TYPE_AUTH
            payloadToFrame = commandProto
        } else if (isEncryptionInitialized) {
            dataType = 1 // DATA_TYPE_ENCRYPTED
            val pktCounter = ++encryptionCounterV1
            val enc = encryptAesCcm(commandProto, pktCounter)
            payloadToFrame = ByteBuffer.allocate(2 + enc.size).order(ByteOrder.LITTLE_ENDIAN)
                .putShort(pktCounter.toShort())
                .put(enc)
                .array()
        } else {
            dataType = 0 // PLAIN
            payloadToFrame = commandProto
        }

        return ByteBuffer.allocate(11 + payloadToFrame.size).order(ByteOrder.LITTLE_ENDIAN).apply {
            put(SPP_V1_PREAMBLE)
            put(0x02.toByte()) // CHANNEL_PROTO_TX = 2
            put(0x80.toByte()) // flag = true
            putShort((payloadToFrame.size + 3).toShort())
            put(0x02.toByte()) // OPCODE_SEND = 2
            put(serial.toByte())
            put(dataType.toByte())
            put(payloadToFrame)
            put(SPP_V1_EPILOGUE)
        }.array()
    }

    /**
     * SPP V2 Session Config Request (`A5 A5 02 00 ...`).
     */
    fun buildSppV2SessionConfigRequest(): ByteArray {
        val payload = byteArrayOf(
            0x01, // OPCODE_START_SESSION_REQUEST
            0x01, 0x03, 0x00, 0x01, 0x00, 0x00, // VERSION 01.00.00
            0x02, 0x02, 0x00, 0x00, 0xFC.toByte(), // MAX_PACKET_SIZE 64512
            0x03, 0x02, 0x00, 0x20, 0x00, // TX_WIN 32
            0x04, 0x02, 0x00, 0x10, 0x27 // SEND_TIMEOUT 10000ms
        )
        return encodeSppV2Frame(SPP_V2_TYPE_SESSION_CONFIG, 0, payload)
    }

    fun buildSppV2Ack(sequenceNumber: Int): ByteArray {
        return encodeSppV2Frame(SPP_V2_TYPE_ACK, sequenceNumber, ByteArray(0))
    }

    fun encodeSppV2Command(commandProto: ByteArray, isAuthPacket: Boolean): ByteArray {
        val seq = sequenceCounterV2++ and 0xFF
        val opCode = if (isAuthPacket || !isEncryptionInitialized) 1 else 2 // 1=Plaintext, 2=Encrypted
        val body = if (opCode == 2) encryptV2Ctr(commandProto) else commandProto
        val dataPayload = ByteBuffer.allocate(2 + body.size).apply {
            put(0x01.toByte()) // CHANNEL_PROTOBUF = 1
            put(opCode.toByte())
            put(body)
        }.array()
        return encodeSppV2Frame(SPP_V2_TYPE_DATA, seq, dataPayload)
    }

    private fun encodeSppV2Frame(packetType: Int, seq: Int, payloadBytes: ByteArray): ByteArray {
        val crc = crc16Arc(payloadBytes)
        return ByteBuffer.allocate(8 + payloadBytes.size).order(ByteOrder.LITTLE_ENDIAN).apply {
            put(SPP_V2_PREAMBLE)
            put((packetType and 0x0F).toByte())
            put((seq and 0xFF).toByte())
            putShort(payloadBytes.size.toShort())
            putShort(crc.toShort())
            put(payloadBytes)
        }.array()
    }

    /**
     * BLE V1 SAR Single-Packet Framing (`00 00 02 <encFlag> [encIdx] <payload>`).
     */
    fun encodeBleSarSingleCommand(commandProto: ByteArray): ByteArray {
        val encrypt = isEncryptionInitialized
        val body = if (encrypt) encryptAesCcm(commandProto, bleEncryptedIndex) else commandProto
        val totalLen = (if (encrypt) 6 else 4) + body.size
        return ByteBuffer.allocate(totalLen).order(ByteOrder.LITTLE_ENDIAN).apply {
            putShort(0) // chunk = 0 (single command)
            put(2.toByte()) // type = 2 (command)
            put((if (encrypt) 1 else 2).toByte())
            if (encrypt) {
                putShort((bleEncryptedIndex++).toShort())
            }
            put(body)
        }.array()
    }

    fun buildBleSarAck(): ByteArray = byteArrayOf(0x00, 0x00, 0x03, 0x00)

    private fun crc16Arc(payload: ByteArray): Int {
        var crc = 0
        for (b in payload) {
            for (j in 0 until 8) {
                crc = crc shl 1
                if ((((crc shr 16) and 1) xor ((b.toInt() shr j) and 1)) == 1) {
                    crc = crc xor 0x8005
                }
            }
        }
        return Integer.reverse(crc) ushr 16
    }
}

