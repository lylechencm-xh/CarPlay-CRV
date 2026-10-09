package com.shilapi.xcertplay.airplay

import org.junit.Assert.assertEquals
import org.junit.Test

class AirPlayEventCommandHeaderTest {
    @Test
    fun reusableHeaderMatchesLegacyStringExactly() {
        val target = ByteArray(AirPlayEventCommandHeader.MAX_BYTES)
        val bodySize = 137
        val cseq = 12_345
        val length = AirPlayEventCommandHeader.write(bodySize, cseq, target)

        val expected =
            "POST /command RTSP/1.0\r\n" +
                "Content-Type: application/x-apple-binary-plist\r\n" +
                "Content-Length: $bodySize\r\n" +
                "CSeq: $cseq\r\n\r\n"

        assertEquals(
            expected,
            String(target, 0, length, Charsets.US_ASCII),
        )
    }

    @Test
    fun zeroValuesAreWrittenWithoutSpecialAllocationPath() {
        val target = ByteArray(AirPlayEventCommandHeader.MAX_BYTES)
        val length = AirPlayEventCommandHeader.write(0, 0, target)

        assertEquals(
            "POST /command RTSP/1.0\r\n" +
                "Content-Type: application/x-apple-binary-plist\r\n" +
                "Content-Length: 0\r\n" +
                "CSeq: 0\r\n\r\n",
            String(target, 0, length, Charsets.US_ASCII),
        )
    }
}
