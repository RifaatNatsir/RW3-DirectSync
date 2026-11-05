package com.rw3.directsync.ble

import java.util.UUID

/**
 * BLE Service, Characteristic, and Descriptor UUIDs for Redmi Watch 3 (Xiaomi / Huami BLE stack).
 * Reference: Gadgetbridge Xiaomi/Huami GATT profile & Bluetooth SIG standard profiles.
 */
object BleUuids {

    // =========================================================================
    // 1. Standard Client Characteristic Configuration Descriptor (CCCD)
    // Required to enable notifications/indications on any GATT characteristic.
    // =========================================================================
    val UUID_DESCRIPTOR_CCCD: UUID =
        UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    // =========================================================================
    // 2. Xiaomi / Huami Proprietary Authentication Service & Characteristic
    // Used for the 3-step AES-128 ECB challenge-response handshake.
    // Service 0xFEE1 is the primary auth envelope; 0x00000009-0000-3512-2118-0009af100700
    // is the Auth Characteristic receiving challenge requests and encrypted responses.
    // =========================================================================
    val UUID_SERVICE_XIAOMI_AUTH: UUID =
        UUID.fromString("0000fee1-0000-1000-8000-00805f9b34fb")

    val UUID_CHAR_XIAOMI_AUTH: UUID =
        UUID.fromString("00000009-0000-3512-2118-0009af100700")

    // Alternative Xiaomi Wear / Mi Fitness SAR/Auth service UUIDs (0xFE95) exposed on newer firmware
    val UUID_SERVICE_MI_WEAR: UUID =
        UUID.fromString("0000fe95-0000-1000-8000-00805f9b34fb")

    // =========================================================================
    // 3. Standard Bluetooth SIG Heart Rate Service (0x180D) & Measurement (0x2A37)
    // Once authenticated, subscribing to 0x2A37 inside service 0x180D streams real-time BPM.
    // =========================================================================
    val UUID_SERVICE_HEART_RATE: UUID =
        UUID.fromString("0000180d-0000-1000-8000-00805f9b34fb")

    val UUID_CHAR_HEART_RATE_MEASUREMENT: UUID =
        UUID.fromString("00002a37-0000-1000-8000-00805f9b34fb")

    val UUID_CHAR_HEART_RATE_CONTROL_POINT: UUID =
        UUID.fromString("00002a39-0000-1000-8000-00805f9b34fb")

    // =========================================================================
    // 4. Activity / Sleep Fetch Data Characteristics (Huami/Xiaomi Sync Profile)
    // =========================================================================
    val UUID_SERVICE_XIAOMI_FITNESS: UUID =
        UUID.fromString("0000fee0-0000-1000-8000-00805f9b34fb")

    val UUID_CHAR_ACTIVITY_CONTROL: UUID =
        UUID.fromString("00000004-0000-3512-2118-0009af100700")

    val UUID_CHAR_ACTIVITY_DATA: UUID =
        UUID.fromString("00000005-0000-3512-2118-0009af100700")
}

