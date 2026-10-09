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


    @Test
    fun reusableOpenerResetsForEachNonce() {
        val key = ByteArray(32) { (it + 7).toByte() }
        val aad = byteArrayOf(8, 7, 6, 5)
        val opener = AirPlayChaChaOpener(key)
        val nonceBuffer = ByteArray(12)

        for (counter in 1L..3L) {
            val nonce = AirPlayCrypto.nonce64(counter)
            val plain = "frame-$counter".toByteArray(Charsets.UTF_8)
            val sealed = AirPlayCrypto.chachaSeal(key, nonce, plain, aad)
            val opened = opener.open(
                nonce = AirPlayCrypto.nonce64(counter, nonceBuffer),
                source = sealed,
                aad = aad,
            )
            assertArrayEquals(plain, opened)
        }
    }


    @Test
    fun reusableOpenerCanWritePlaintextAfterPrefix() {
        val key = ByteArray(32) { (it + 11).toByte() }
        val nonce = ByteArray(12) { (it + 2).toByte() }
        val aad = byteArrayOf(4, 3, 2, 1)
        val plain = byteArrayOf(10, 20, 30, 40, 50)
        val prefix = byteArrayOf(1, 2, 3, 4, 5, 6)
        val sealed = AirPlayCrypto.chachaSeal(key, nonce, plain, aad)

        val combined = AirPlayChaChaOpener(key).openWithPrefix(
            nonce = nonce,
            source = sealed,
            offset = 0,
            length = sealed.size,
            aad = aad,
            prefixSource = prefix,
            prefixLength = prefix.size,
        )

        assertArrayEquals(prefix + plain, combined)
    }
}
