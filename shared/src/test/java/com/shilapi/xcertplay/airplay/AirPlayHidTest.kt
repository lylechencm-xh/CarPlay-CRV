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


    @Test
    fun primitiveSingleTouchMatchesOneContactReport() {
        val width = 1280
        val height = 720
        val x = 0.42
        val y = 0.63
        val expected = AirPlayHid.touchReport(
            listOf(AirPlayContact(0, x, y, true)),
            width,
            height,
        )

        val actual = AirPlayHid.touchReport(x, y, true, width, height)

        assertArrayEquals(expected, actual)
    }


    @Test
    fun primitiveTwoTouchMatchesTwoContactReport() {
        val width = 1280
        val height = 720
        val contacts = listOf(
            AirPlayContact(0, 0.20, 0.30, true),
            AirPlayContact(1, 0.80, 0.70, true),
        )
        val expected = AirPlayHid.touchReport(contacts, width, height)

        val actual = AirPlayHid.touchReport(
            x0 = 0.20,
            y0 = 0.30,
            down0 = true,
            x1 = 0.80,
            y1 = 0.70,
            down1 = true,
            xMax = width,
            yMax = height,
        )

        assertArrayEquals(expected, actual)
    }
}
