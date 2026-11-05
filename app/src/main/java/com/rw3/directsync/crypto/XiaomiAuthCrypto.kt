package com.rw3.directsync.crypto

import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

/**
 * Cryptography utility implementing the Huami / Xiaomi BLE Authentication protocol
 * modeled after Gadgetbridge's `HuamiSupport` / `AbstractMiBandService` handshake.
 *
 * Protocol Overview (3-Step Challenge-Response over `UUID_CHAR_XIAOMI_AUTH`):
 * 1. Client -> Watch: Request 16-byte random challenge (`[0x02, 0x08]` or `[0x02, 0x00]`).
 * 2. Watch -> Client: Notification `[0x10, 0x02, 0x01, <16-byte random challenge>]`.
 * 3. Client -> Watch: `[0x03, 0x08 (or 0x00), <16-byte AES-128/ECB/NoPadding(AuthKey, Challenge)>]`.
 * 4. Watch -> Client: Notification `[0x10, 0x03, 0x01]` indicating authentication success.
 */
object XiaomiAuthCrypto {

    private const val AES_TRANSFORMATION = "AES/ECB/NoPadding"
    private const val EXPECTED_HEX_KEY_LENGTH = 32 // 32 hex chars = 16 bytes (128-bit AES key)
    private const val CHALLENGE_BYTE_LENGTH = 16

    // Protocol command constants (Gadgetbridge Huami/Xiaomi Auth specification)
    const val AUTH_SEND_KEY: Byte = 0x01
    const val AUTH_REQUEST_RANDOM_AUTH_NUMBER: Byte = 0x02
    const val AUTH_SEND_ENCRYPTED_AUTH_NUMBER: Byte = 0x03
    const val AUTH_RESPONSE: Byte = 0x10
    const val AUTH_SUCCESS: Byte = 0x01
    const val AUTH_FAIL: Byte = 0x04

    // Cryptographic flag byte used by Xiaomi / Huami devices (0x00 = standard, 0x08 = newer AuthKey mode)
    const val CRYPT_FLAGS: Byte = 0x00

    /**
     * Converts the user-provided 32-character hexadecimal Auth Key (e.g. "00112233445566778899aabbccddeeff")
     * into a raw 16-byte array suitable for AES-128 `SecretKeySpec`.
     *
     * Accepts optional "0x" prefix and ignores whitespace.
     */
    @Throws(IllegalArgumentException::class)
    fun parseAuthKeyHex(authKeyHex: String): ByteArray {
        val sanitized = authKeyHex
            .trim()
            .removePrefix("0x")
            .removePrefix("0X")
            .replace(" ", "")

        require(sanitized.length == EXPECTED_HEX_KEY_LENGTH) {
            "Invalid Auth Key length: expected $EXPECTED_HEX_KEY_LENGTH hex characters (16 bytes), got ${sanitized.length}."
        }
        require(sanitized.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) {
            "Auth Key contains non-hexadecimal characters."
        }

        return ByteArray(EXPECTED_HEX_KEY_LENGTH / 2) { i ->
            val index = i * 2
            sanitized.substring(index, index + 2).toInt(16).toByte()
        }
    }

    /**
     * Encrypts the 16-byte challenge received from Redmi Watch 3 using AES-128 in ECB mode with NoPadding.
     *
     * @param challenge 16-byte random challenge extracted from the watch's auth notification payload.
     * @param authKeyHex 32-character hexadecimal Auth Key extracted from Mi Fitness / server token.
     * @return 16-byte encrypted challenge block.
     */
    @Throws(Exception::class)
    fun encryptChallenge(challenge: ByteArray, authKeyHex: String): ByteArray {
        require(challenge.size == CHALLENGE_BYTE_LENGTH) {
            "AES-128 ECB/NoPadding requires a $CHALLENGE_BYTE_LENGTH-byte challenge block, got ${challenge.size} bytes."
        }

        // Convert 32-char hex string into 16-byte AES-128 key
        val secretKeyBytes = parseAuthKeyHex(authKeyHex)
        val secretKeySpec = SecretKeySpec(secretKeyBytes, "AES")

        val cipher = Cipher.getInstance(AES_TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKeySpec)
        return cipher.doFinal(challenge)
    }

    /** Decrypts explicitly encrypted activity blocks without padding. */
    fun decryptActivityPayload(payload: ByteArray, authKeyHex: String): ByteArray {
        require(payload.isNotEmpty() && payload.size % 16 == 0) {
            "Encrypted activity payload must contain complete 16-byte blocks"
        }
        val cipher = Cipher.getInstance(AES_TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(parseAuthKeyHex(authKeyHex), "AES"))
        return cipher.doFinal(payload)
    }

    /**
     * Builds Step 1 of the Auth Handshake: Request a 16-byte random challenge from the watch.
     */
    fun buildRequestRandomChallengePacket(cryptFlags: Byte = CRYPT_FLAGS): ByteArray {
        return byteArrayOf(AUTH_REQUEST_RANDOM_AUTH_NUMBER, cryptFlags)
    }

    /**
     * Builds Step 3 of the Auth Handshake: Send the AES-128 encrypted challenge back to the watch.
     * Packet layout (18 bytes total):
     * - Byte 0: 0x03 (AUTH_SEND_ENCRYPTED_AUTH_NUMBER)
     * - Byte 1: cryptFlags (0x00 or 0x08)
     * - Bytes 2..17: 16-byte AES-ECB encrypted challenge
     */
    fun buildEncryptedResponsePacket(
        challenge: ByteArray,
        authKeyHex: String,
        cryptFlags: Byte = CRYPT_FLAGS
    ): ByteArray {
        val encryptedChallenge = encryptChallenge(challenge, authKeyHex)
        return ByteArray(2 + encryptedChallenge.size).apply {
            this[0] = AUTH_SEND_ENCRYPTED_AUTH_NUMBER
            this[1] = cryptFlags
            System.arraycopy(encryptedChallenge, 0, this, 2, encryptedChallenge.size)
        }
    }
}

