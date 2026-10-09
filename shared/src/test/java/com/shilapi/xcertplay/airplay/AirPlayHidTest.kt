package com.shilapi.xcertplay.airplay

import org.junit.Assert.assertArrayEquals
import org.junit.Test

class AirPlayHidTest {
    @Test
    fun scaledTouchReportMatchesLegacyScaledContacts() {
        val contacts = listOf(
            AirPlayContact(0, 0.25, 0.50, true),
            AirPlayContact(1, 0.75, 0.20, false),
        )
        val width = 1280
        val height = 720

        val legacy = AirPlayHid.touchReport(
            contacts.map {
                it.copy(
                    x = it.x * width,
                    y = it.y * height,
                )
            },
        )
        val direct = AirPlayHid.touchReport(contacts, width, height)

        assertArrayEquals(legacy, direct)
    }
}
