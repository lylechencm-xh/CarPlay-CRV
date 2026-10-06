package com.shilapi.xcertplay

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CrvLegacyRtpTest {
    @Test
    fun fixedHeaderPayload() {
        val packet = header(0x80) + byteArrayOf(1, 2, 3)
        assertArrayEquals(byteArrayOf(1, 2, 3), CrvLegacyRtp.payload(packet))
    }

    @Test
    fun skipsCsrcEntries() {
        val packet =
            header(0x82) +
                ByteArray(8) { (it + 1).toByte() } +
                byteArrayOf(9, 10)
        assertArrayEquals(byteArrayOf(9, 10), CrvLegacyRtp.payload(packet))
    }

    @Test
    fun skipsExtension() {
        val extension = byteArrayOf(
            0x12, 0x34, 0x00, 0x01,
            0x55, 0x66, 0x77, 0x01,
        )
        val packet = header(0x90) + extension + byteArrayOf(11, 12)
        assertArrayEquals(byteArrayOf(11, 12), CrvLegacyRtp.payload(packet))
    }

    @Test
    fun stripsPadding() {
        val packet =
            header(0xa0) +
                byteArrayOf(21, 22) +
                byteArrayOf(0, 0, 0, 4)
        assertArrayEquals(byteArrayOf(21, 22), CrvLegacyRtp.payload(packet))
    }

    @Test
    fun rejectsMalformedExtensionAndVersion() {
        assertNull(CrvLegacyRtp.payload(header(0x40) + byteArrayOf(1)))
        assertNull(CrvLegacyRtp.payload(header(0x90) + byteArrayOf(0, 0, 0)))
    }

    private fun header(first: Int): ByteArray =
        ByteArray(12).also {
            it[0] = first.toByte()
            it[1] = 96.toByte()
        }
}
