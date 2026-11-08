package com.rw3.directsync.watchface

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class WatchfacePackageValidatorTest {

    @Test
    fun validate_rejectsTooSmallFile() {
        val tiny = ByteArray(64) { 0 }
        val result = WatchfacePackageValidator.validate("test.bin", tiny)
        assertTrue(result is WatchfacePackageValidator.ValidationResult.Invalid)
        assertTrue((result as WatchfacePackageValidator.ValidationResult.Invalid).reason.contains("terlalu kecil"))
    }

    @Test
    fun validate_rejectsFirmwareKeywords() {
        val data = ByteArray(1024) { 0x11 }
        val res1 = WatchfacePackageValidator.validate("redmi_watch3_firmware_v1.0.bin", data)
        assertTrue(res1 is WatchfacePackageValidator.ValidationResult.Invalid)
        assertTrue((res1 as WatchfacePackageValidator.ValidationResult.Invalid).reason.contains("firmware"))

        val res2 = WatchfacePackageValidator.validate("update.fw", data)
        assertTrue(res2 is WatchfacePackageValidator.ValidationResult.Invalid)
    }

    @Test
    fun validate_rejectsFirmwareHeaderSignatures() {
        val data = ByteArray(1024) { 0 }
        // Set AMOI magic signature
        data[0] = 0x41
        data[1] = 0x4D
        data[2] = 0x4F
        data[3] = 0x49
        val res = WatchfacePackageValidator.validate("clean_name.bin", data)
        assertTrue(res is WatchfacePackageValidator.ValidationResult.Invalid)
        assertTrue((res as WatchfacePackageValidator.ValidationResult.Invalid).reason.contains("signature header"))
    }

    @Test
    fun validate_acceptsValidBinaryPackage() {
        val data = ByteArray(2048) { 0 }
        // Write a fake watchface name at offset 104
        val nameBytes = "Cyberpunk Mint".toByteArray(Charsets.UTF_8)
        System.arraycopy(nameBytes, 0, data, 104, nameBytes.size)

        val result = WatchfacePackageValidator.validate("dial_cyberpunk.bin", data)
        assertTrue(result is WatchfacePackageValidator.ValidationResult.Valid)

        val valid = result as WatchfacePackageValidator.ValidationResult.Valid
        assertEquals("Cyberpunk Mint", valid.name)
        assertEquals(2048, valid.sizeBytes)
        assertEquals(16, valid.md5Bytes.size)
        assertEquals(32, valid.md5Hex.length)
    }

    @Test
    fun validate_acceptsValidZipPackageWithWfDef() {
        val bos = ByteArrayOutputStream()
        ZipOutputStream(bos).use { zos ->
            zos.putNextEntry(ZipEntry("wfDef.json"))
            zos.write("{\"title\":\"Album Neon\"}".toByteArray(Charsets.UTF_8))
            zos.closeEntry()

            zos.putNextEntry(ZipEntry("images/bg.png"))
            zos.write(ByteArray(256) { 0x55 })
            zos.closeEntry()
        }
        val zipBytes = bos.toByteArray()
        assertTrue(zipBytes.size >= 128)

        val result = WatchfacePackageValidator.validate("neon_album.zip", zipBytes)
        assertTrue(result is WatchfacePackageValidator.ValidationResult.Valid)

        val valid = result as WatchfacePackageValidator.ValidationResult.Valid
        assertTrue(valid.isZip)
        assertEquals(zipBytes.size, valid.sizeBytes)
    }
}

