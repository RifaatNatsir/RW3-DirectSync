package com.rw3.directsync.crypto

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class XiaomiAuthCryptoTest {

    @Test
    fun parseAuthKeyHex_valid32Char_parsesSuccessfully() {
        val hex = "00112233445566778899aabbccddeeff"
        val bytes = XiaomiAuthCrypto.parseAuthKeyHex(hex)
        assertEquals(16, bytes.size)
        assertEquals(0x00.toByte(), bytes[0])
        assertEquals(0x11.toByte(), bytes[1])
        assertEquals(0xFF.toByte(), bytes[15])
    }

    @Test(expected = IllegalArgumentException::class)
    fun parseAuthKeyHex_invalidLength_throwsException() {
        XiaomiAuthCrypto.parseAuthKeyHex("00112233")
    }

    @Test
    fun encryptChallenge_deterministicAesEcbOutput() {
        val keyHex = "0123456789abcdef0123456789abcdef"
        val challenge = ByteArray(16) { it.toByte() }

        val encrypted1 = XiaomiAuthCrypto.encryptChallenge(challenge, keyHex)
        val encrypted2 = XiaomiAuthCrypto.encryptChallenge(challenge, keyHex)

        assertNotNull(encrypted1)
        assertEquals(16, encrypted1.size)
        assertArrayEquals(encrypted1, encrypted2)
    }

    @Test
    fun buildEncryptedResponsePacket_properLayout() {
        val keyHex = "0123456789abcdef0123456789abcdef"
        val challenge = ByteArray(16) { 0xAA.toByte() }

        val packet = XiaomiAuthCrypto.buildEncryptedResponsePacket(challenge, keyHex)
        assertEquals(18, packet.size)
        assertEquals(XiaomiAuthCrypto.AUTH_SEND_ENCRYPTED_AUTH_NUMBER, packet[0]) // 0x03
        assertEquals(XiaomiAuthCrypto.CRYPT_FLAGS, packet[1]) // 0x00
    }
}

