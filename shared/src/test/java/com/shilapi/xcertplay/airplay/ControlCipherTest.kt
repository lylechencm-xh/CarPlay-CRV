package com.shilapi.xcertplay.airplay

import org.junit.Assert.assertArrayEquals
import org.junit.Test

class ControlCipherTest {
    @Test
    fun twoPartEncryptMatchesLegacySinglePlaintextFrame() {
        val key = ByteArray(32) { (it + 1).toByte() }
        val first = "POST /command RTSP/1.0\r\nContent-Length: 5\r\n\r\n"
            .toByteArray(Charsets.US_ASCII)
        val second = byteArrayOf(1, 2, 3, 4, 5)

        val legacy = ControlCipher(key, key).encrypt(first + second)
        val fast = ControlCipher(key, key).encrypt(first, second)

        assertArrayEquals(legacy, fast)
    }

    @Test
    fun twoPartFramesDecryptToOriginalPlaintextAcrossCounters() {
        val key = ByteArray(32) { (it + 9).toByte() }
        val writer = ControlCipher(key, key)
        val reader = ControlCipher(key, key)

        for (index in 0 until 3) {
            val first = "head-$index:".toByteArray(Charsets.US_ASCII)
            val second = ByteArray(32 + index) { (it + index).toByte() }
            val encrypted = writer.encrypt(first, second)
            val decrypted = reader.decrypt(encrypted)

            assertArrayEquals(first + second, decrypted.data)
            assertArrayEquals(ByteArray(0), decrypted.rest)
        }
    }
}
