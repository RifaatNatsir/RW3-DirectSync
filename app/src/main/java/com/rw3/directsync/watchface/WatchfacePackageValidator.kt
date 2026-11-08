package com.rw3.directsync.watchface

import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Locale
import java.util.zip.ZipInputStream

/**
 * Validates imported watchface packages (.bin, .face, .zip) for Redmi Watch 3:
 * - Ensures file is not a dangerous firmware image (firmware protection).
 * - Enforces minimum and maximum file size boundaries.
 * - Extracts watchface display name and unique identifier (offset 0x28 in 5A A5 header).
 * - Computes MD5 checksum required by Xiaomi Wear DataUpload protocol.
 */
object WatchfacePackageValidator {

    const val MIN_PACKAGE_SIZE = 128
    const val MAX_PACKAGE_SIZE = 10 * 1024 * 1024 // 10 MB limit for watchface binaries

    private val FORBIDDEN_NAME_KEYWORDS = listOf("firmware", ".fw", ".img", "bootloader", "recovery", "ota_update")
    private val FORBIDDEN_HEADER_SIGNATURES = listOf(
        byteArrayOf(0x41, 0x4D, 0x4F, 0x49), // "AMOI"
        byteArrayOf(0x58, 0x4D, 0x46, 0x57), // "XMFW"
        byteArrayOf(0x4D, 0x49, 0x46, 0x49)  // "MIFI"
    )

    sealed class ValidationResult {
        data class Valid(
            val name: String,
            val id: String,
            val sizeBytes: Int,
            val md5Bytes: ByteArray,
            val md5Hex: String,
            val isZip: Boolean,
            val description: String
        ) : ValidationResult() {
            override fun equals(other: Any?): Boolean {
                if (this === other) return true
                if (javaClass != other?.javaClass) return false
                other as Valid
                return id == other.id && sizeBytes == other.sizeBytes && md5Bytes.contentEquals(other.md5Bytes)
            }

            override fun hashCode(): Int {
                var result = id.hashCode()
                result = 31 * result + sizeBytes
                result = 31 * result + md5Bytes.contentHashCode()
                return result
            }
        }

        data class Invalid(val reason: String) : ValidationResult()
    }

    /**
     * If [data] is a ZIP archive containing a `.bin` or `.face` watchface binary,
     * extracts and returns that binary so it can be uploaded directly to the watch.
     * Otherwise returns [data] unchanged.
     */
    fun extractUploadableBinary(data: ByteArray): ByteArray {
        val isZipMagic = data.size >= 4 && data[0] == 0x50.toByte() && data[1] == 0x4B.toByte() &&
                data[2] == 0x03.toByte() && data[3] == 0x04.toByte()
        if (!isZipMagic) return data

        try {
            var largestBinEntry: ByteArray? = null
            ZipInputStream(ByteArrayInputStream(data)).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    val lower = entry.name.lowercase(Locale.ROOT)
                    if (!entry.isDirectory && (lower.endsWith(".bin") || lower.endsWith(".face"))) {
                        val bytes = zis.readBytes()
                        if (bytes.size >= MIN_PACKAGE_SIZE &&
                            (largestBinEntry == null || bytes.size > largestBinEntry!!.size)
                        ) {
                            largestBinEntry = bytes
                        }
                    }
                    zis.closeEntry()
                    entry = zis.nextEntry
                }
            }
            if (largestBinEntry != null) {
                return largestBinEntry!!
            }
        } catch (_: Exception) {
        }
        return data
    }

    fun validate(fileName: String, data: ByteArray): ValidationResult {
        val lowerName = fileName.lowercase(Locale.ROOT)

        // 1. Enforce size limits
        if (data.size < MIN_PACKAGE_SIZE) {
            return ValidationResult.Invalid("Ukuran berkas terlalu kecil (${data.size} byte). Minimal $MIN_PACKAGE_SIZE byte.")
        }
        if (data.size > MAX_PACKAGE_SIZE) {
            val mb = data.size / (1024 * 1024.0)
            return ValidationResult.Invalid("Ukuran berkas melebihi batas (%.1f MB). Maksimal 10 MB.".format(mb))
        }

        // 2. Reject firmware files or dangerous images
        for (kw in FORBIDDEN_NAME_KEYWORDS) {
            if (lowerName.contains(kw)) {
                return ValidationResult.Invalid("Berkas ditolak: nama mengandung kata kunci firmware atau pembaruan sistem ($kw).")
            }
        }
        for (sig in FORBIDDEN_HEADER_SIGNATURES) {
            if (data.size >= sig.size && data.copyOfRange(0, sig.size).contentEquals(sig)) {
                return ValidationResult.Invalid("Berkas ditolak: signature header cocok dengan berkas firmware sistem Xiaomi.")
            }
        }

        // 3. Compute MD5 checksum
        val md5Bytes = try {
            val md = MessageDigest.getInstance("MD5")
            md.digest(data)
        } catch (e: Exception) {
            return ValidationResult.Invalid("Gagal menghitung checksum MD5: ${e.message}")
        }
        val md5Hex = md5Bytes.joinToString("") { "%02x".format(it) }

        // 4. Validate ZIP container (e.g. wfDef.json / resource.bin / .bin / images)
        val isZipMagic = data.size >= 4 && data[0] == 0x50.toByte() && data[1] == 0x4B.toByte() &&
                data[2] == 0x03.toByte() && data[3] == 0x04.toByte()

        if (isZipMagic || lowerName.endsWith(".zip")) {
            return validateZipPackage(fileName, data, md5Bytes, md5Hex)
        }

        // 5. Validate Binary container (.bin, .face)
        return validateBinaryPackage(fileName, data, md5Bytes, md5Hex)
    }

    private fun validateBinaryPackage(
        fileName: String,
        data: ByteArray,
        md5Bytes: ByteArray,
        md5Hex: String
    ): ValidationResult {
        var extractedName = ""
        var extractedId = ""
        try {
            if (data.size >= 164) {
                // Extract Watchface ID from offset 0x28 (40) up to 64 bytes (XiaomiFWHelper format)
                val rawId = extractZeroTerminatedUtf8(data, 40, 64)
                if (rawId.isNotEmpty() && rawId.all { it.isLetterOrDigit() || it == '_' || it == '-' }) {
                    extractedId = rawId
                }

                var isAllFF = true
                for (i in 0 until 4) {
                    if ((data[104 + i].toInt() and 0xFF) != 0xFF) {
                        isAllFF = false
                        break
                    }
                }
                if (!isAllFF) {
                    extractedName = extractZeroTerminatedUtf8(data, 104, 60)
                }
                if (extractedName.isBlank()) {
                    val readOffset = readInt32LE(data, 116)
                    if (readOffset in 0..(data.size - 24)) {
                        val strOffset = readOffset + 20 + readInt16LE(data, readOffset + 8)
                        val strLen = readInt16LE(data, readOffset + 12)
                        if (strOffset in 0..(data.size - strLen) && strLen in 1..120) {
                            extractedName = String(data, strOffset, strLen, StandardCharsets.UTF_8).trim()
                        }
                    }
                }
            }
        } catch (_: Exception) {}

        val finalName = extractedName.ifBlank {
            fileName.substringBeforeLast('.').replace('_', ' ').replace('-', ' ').trim()
        }

        val baseId = extractedId.ifBlank { "wf_" + md5Hex.take(12) }

        return ValidationResult.Valid(
            name = finalName,
            id = baseId,
            sizeBytes = data.size,
            md5Bytes = md5Bytes,
            md5Hex = md5Hex,
            isZip = false,
            description = "Paket Watchface Biner Xiaomi/Redmi (%.1f KB)".format(data.size / 1024.0)
        )
    }

    private fun validateZipPackage(
        fileName: String,
        data: ByteArray,
        md5Bytes: ByteArray,
        md5Hex: String
    ): ValidationResult {
        var hasValidEntry = false
        var extractedName = ""
        var extractedId = ""
        var totalUncompressed = 0L

        try {
            ZipInputStream(ByteArrayInputStream(data)).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    val name = entry.name.lowercase(Locale.ROOT)
                    if (name.contains("wfdef.json") || name.contains("manifest.json") ||
                        name.contains("resource.bin") || name.endsWith(".bin") ||
                        name.endsWith(".face") || name.startsWith("images/") ||
                        name.endsWith(".png")
                    ) {
                        hasValidEntry = true
                    }
                    if (!entry.isDirectory && (name.endsWith(".bin") || name.endsWith(".face"))) {
                        val binBytes = zis.readBytes()
                        totalUncompressed += binBytes.size
                        if (binBytes.size >= 164) {
                            val idCandidate = extractZeroTerminatedUtf8(binBytes, 40, 64)
                            if (idCandidate.isNotEmpty() && idCandidate.all { it.isLetterOrDigit() || it == '_' }) {
                                extractedId = idCandidate
                            }
                            val nameCandidate = extractZeroTerminatedUtf8(binBytes, 104, 60)
                            if (nameCandidate.isNotBlank() && !nameCandidate.any { it.code == 0xFFFD }) {
                                extractedName = nameCandidate
                            }
                        }
                    } else {
                        totalUncompressed += entry.size.coerceAtLeast(0)
                    }
                    if (totalUncompressed > 30 * 1024 * 1024) {
                        return ValidationResult.Invalid("Ukuran dekompresi ZIP melebihi batas keamanan 30 MB.")
                    }
                    zis.closeEntry()
                    entry = zis.nextEntry
                }
            }
        } catch (e: Exception) {
            return ValidationResult.Invalid("Format berkas ZIP tidak dapat dibaca: ${e.message}")
        }

        if (!hasValidEntry) {
            return ValidationResult.Invalid("Paket ZIP tidak berisi komponen watchface Xiaomi yang valid (wfDef.json, resource.bin, .bin, atau gambar).")
        }

        val finalName = extractedName.ifBlank {
            fileName.substringBeforeLast('.').replace('_', ' ').replace('-', ' ').trim()
        }
        val baseId = extractedId.ifBlank { "wf_zip_" + md5Hex.take(12) }

        return ValidationResult.Valid(
            name = finalName,
            id = baseId,
            sizeBytes = data.size,
            md5Bytes = md5Bytes,
            md5Hex = md5Hex,
            isZip = true,
            description = "Paket Watchface Arsip ZIP Xiaomi (%.1f KB)".format(data.size / 1024.0)
        )
    }

    private fun extractZeroTerminatedUtf8(b: ByteArray, offset: Int, maxLen: Int): String {
        var len = 0
        while (len < maxLen && offset + len < b.size && b[offset + len] != 0.toByte()) {
            len++
        }
        return if (len > 0) String(b, offset, len, StandardCharsets.UTF_8).trim() else ""
    }

    private fun readInt16LE(b: ByteArray, offset: Int): Int {
        if (offset + 2 > b.size) return 0
        return (b[offset].toInt() and 0xFF) or ((b[offset + 1].toInt() and 0xFF) shl 8)
    }

    private fun readInt32LE(b: ByteArray, offset: Int): Int {
        if (offset + 4 > b.size) return 0
        return (b[offset].toInt() and 0xFF) or
                ((b[offset + 1].toInt() and 0xFF) shl 8) or
                ((b[offset + 2].toInt() and 0xFF) shl 16) or
                ((b[offset + 3].toInt() and 0xFF) shl 24)
    }
}
