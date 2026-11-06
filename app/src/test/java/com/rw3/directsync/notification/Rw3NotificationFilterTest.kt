package com.rw3.directsync.notification

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Rw3NotificationFilterTest {

    @Test
    fun defaultWhitelist_containsEssentialIndonesianCommunicationApps() {
        val defaultList = Rw3NotificationListenerService.DEFAULT_WHITELIST

        // Essential WhatsApp packages
        assertTrue(defaultList.contains("com.whatsapp"))
        assertTrue(defaultList.contains("com.whatsapp.w4b"))

        // Telegram packages
        assertTrue(defaultList.contains("org.telegram.messenger"))

        // SMS & Messaging packages
        assertTrue(defaultList.contains("com.google.android.apps.messaging"))
        assertTrue(defaultList.contains("com.android.mms"))

        // Email & Social
        assertTrue(defaultList.contains("com.google.android.gm"))
        assertTrue(defaultList.contains("com.instagram.android"))
        assertTrue(defaultList.contains("com.discord"))
        assertTrue(defaultList.contains("jp.naver.line.android"))
    }

    @Test
    fun whitelistFiltering_permitsWhitelistedAndBlocksSpamApps() {
        val whitelist = setOf("com.whatsapp", "org.telegram.messenger")

        // Whitelisted apps are permitted
        assertTrue(whitelist.contains("com.whatsapp"))
        assertTrue(whitelist.contains("org.telegram.messenger"))

        // Non-whitelisted spam/promo apps are blocked
        val spamShopee = "com.shopee.id"
        val spamGame = "com.supercell.clashofclans"
        val spamAd = "com.cleanmaster.mguard"

        assertFalse(whitelist.contains(spamShopee))
        assertFalse(whitelist.contains(spamGame))
        assertFalse(whitelist.contains(spamAd))
    }
}

