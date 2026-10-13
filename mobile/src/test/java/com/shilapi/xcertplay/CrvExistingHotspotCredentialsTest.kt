package com.shilapi.xcertplay

import org.junit.Assert.*
import org.junit.Test

class CrvExistingHotspotCredentialsTest {
    @Test fun acceptsVisibleWpaCredentialsAndNormalizesFrameworkQuotes() {
        val credentials = CrvExistingHotspotCredentials.from("\"Honda\"", "\"12345678\"", true)
        assertEquals("Honda", credentials.ssid)
        assertEquals("12345678", credentials.passphrase)
        assertFalse(credentials.toString().contains("12345678"))
    }

    @Test fun rejectsOpenApHiddenPasswordAndUnusableCredentials() {
        for ((name, secret, wpa) in listOf(
            Triple("Honda", "12345678", false), Triple("Honda", "********", true),
            Triple("Honda", null, true), Triple("", "12345678", true),
            Triple("Honda", "short", true), Triple("界".repeat(11), "12345678", true),
        )) {
            assertThrows(IllegalArgumentException::class.java) {
                CrvExistingHotspotCredentials.from(name, secret, wpa)
            }
        }
    }
}
