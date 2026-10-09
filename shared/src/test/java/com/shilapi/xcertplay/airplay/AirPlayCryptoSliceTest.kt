package com.shilapi.xcertplay.airplay

import org.junit.Assert.assertArrayEquals
import org.junit.Test

class AirPlayCryptoSliceTest {
    @Test
    fun decryptsCiphertextFromOffsetWithoutCopyingTheSlice() {
        val key = ByteArray(32) { (it + 1).toByte() }
        val nonce = ByteArray(12) { (it + 3).toByte() }
        val aad = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)
        val plain = "carplay-audio".toByteArray(Charsets.UTF_8)
        val sealed = AirPlayCrypto.chachaSeal(key, nonce, plain, aad)
        val source = ByteArray(sealed.size + 9)
        sealed.copyInto(source, 5)

        val opened = AirPlayCrypto.chachaOpen(
            key = key,
            nonce = nonce,
            source = source,
            offset = 5,
            length = sealed.size,
            aad = aad,
        )

        assertArrayEquals(plain, opened)
    }
}
