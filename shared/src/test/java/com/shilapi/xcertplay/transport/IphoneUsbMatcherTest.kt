package com.shilapi.xcertplay.transport

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IphoneUsbMatcherTest {
    private val matcher = IphoneUsbMatcher.appleVendor()

    @Test
    fun appleVendorMatchesIphoneProducts() {
        assertTrue(matcher.matches(0x05ac, 0x12a8))
        assertTrue(matcher.matches(0x05ac, 0x12ab))
    }

    @Test
    fun massStorageVendorsAreNeverClassifiedAsIphone() {
        assertFalse(matcher.matches(0x0781, 0x5581)) // SanDisk
        assertFalse(matcher.matches(0x1058, 0x25a2)) // Western Digital
        assertFalse(matcher.matches(0x174c, 0x55aa)) // ASMedia USB/SATA bridge
    }
}
