package com.shilapi.xcertplay.airplay

import org.bouncycastle.crypto.InvalidCipherTextException
import org.junit.Assert.*
import org.junit.Test

/** Fixed synthetic corpus, not captured traffic. Host timings are not vehicle performance results. */
class MediaCryptoReplayTest {
    @Test fun sameAudioAndVideoCorpusAuthenticatesWithEverySamplingModeAndOwnershipStrategy() {
        val key = ByteArray(32) { (it * 17).toByte() }
        val aad = ByteArray(8) { (it * 3).toByte() }
        val prefix = ByteArray(12) { (it + 20).toByte() }
        // Generate each unique key/nonce pair once, outside the replay loop.
        val corpus = listOf(0, 1, 1416, 32768, 262144).mapIndexed { index, size ->
            val nonce = AirPlayCrypto.nonce64(index + 1L)
            val plain = ByteArray(size) { (it * 7 + index).toByte() }
            Triple(nonce, plain, AirPlayCrypto.chachaSeal(key, nonce, plain, aad))
        }
        for (mode in MediaCryptoDiagnostics.Sampling.values()) {
            for (reuse in listOf(false, true)) {
                val reports = mutableListOf<String>()
                fun opener() = AirPlayChaChaOpener(key, MediaDecryptProfiler("replay",
                    { reports.add(it) }, System::nanoTime, System::nanoTime, mode, seed = 123))
                val shared = opener()
                repeat(32) {
                    for ((nonce, plain, sealed) in corpus) {
                        val worker = if (reuse) shared else opener()
                        val opened = worker.openWithPrefix(nonce, sealed, 0, sealed.size,
                            aad, prefix, prefix.size)
                        assertArrayEquals("mode=$mode reuse=$reuse", prefix + plain, opened)
                        // A later call must never overwrite a result retained by the consumer.
                        val retained = opened.copyOf()
                        worker.open(nonce, sealed, aad = aad)
                        assertArrayEquals(retained, opened)
                    }
                }
                if (mode == MediaCryptoDiagnostics.Sampling.OFF) assertTrue(reports.isEmpty())
                else assertTrue(reports.any { it.contains("schema=2") && it.contains("trimCpuUs=") })
            }
        }
    }

    @Test fun failedAuthenticationIsReportedSeparatelyAndNextSampleStillAuthenticates() {
        val key = ByteArray(32) { it.toByte() }
        val nonce = AirPlayCrypto.nonce64(1)
        val aad = byteArrayOf(1, 2, 3)
        val plain = ByteArray(1416) { it.toByte() }
        val sealed = AirPlayCrypto.chachaSeal(key, nonce, plain, aad)
        val bad = sealed.copyOf().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }
        val reports = mutableListOf<String>()
        val opener = AirPlayChaChaOpener(key, MediaDecryptProfiler("test", { reports.add(it) },
            System::nanoTime, { 1L }, MediaCryptoDiagnostics.Sampling.FIXED))
        try {
            opener.open(nonce, bad, aad = aad)
            fail("unauthenticated plaintext must not be returned")
        } catch (_: InvalidCipherTextException) { }
        repeat(128) { assertArrayEquals(plain, opener.open(nonce, sealed, aad = aad)) }
        assertTrue(reports.single().contains("samples=1 sealedBytes=1432 failedSamples=1"))
    }
}
