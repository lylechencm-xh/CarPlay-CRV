package com.shilapi.xcertplay.airplay

import org.junit.Assert.assertArrayEquals
import org.junit.Test

class AirPlayHidCommandPlistTest {
    @Test
    fun templatedHidBodiesMatchGenericBplistEncoding() {
        val cases = listOf(
            AirPlayHid.TOUCH_HID_UID to ByteArray(12) { (it * 7).toByte() },
            AirPlayHid.KNOB_HID_UID to byteArrayOf(1, 2, 3, 4),
            AirPlayHid.MEDIA_HID_UID to byteArrayOf(5),
            AirPlayHid.TELEPHONY_HID_UID to byteArrayOf(6),
        )

        for ((uid, report) in cases) {
            val expected = BplistCodec.encode(
                linkedMapOf(
                    "type" to "hidSendReport",
                    "uuid" to uid.toString(16),
                    "hidReport" to report,
                ),
            )
            assertArrayEquals(expected, AirPlayHidCommandPlist.encode(uid, report))
        }
    }

    @Test
    fun templateCanBeReusedWithChangingTouchPayloads() {
        val first = ByteArray(12) { it.toByte() }
        val second = ByteArray(12) { (100 + it).toByte() }

        val encodedFirst = AirPlayHidCommandPlist.encode(AirPlayHid.TOUCH_HID_UID, first)
        val encodedSecond = AirPlayHidCommandPlist.encode(AirPlayHid.TOUCH_HID_UID, second)

        val decodedFirst = BplistCodec.decode(encodedFirst) as Map<*, *>
        val decodedSecond = BplistCodec.decode(encodedSecond) as Map<*, *>
        assertArrayEquals(first, decodedFirst["hidReport"] as ByteArray)
        assertArrayEquals(second, decodedSecond["hidReport"] as ByteArray)
    }
}
