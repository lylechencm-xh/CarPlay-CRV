package com.shilapi.xcertplay

import org.junit.Assert.*
import org.junit.Test

class CrvNavigationPcmGainTest {
    @Test fun originalVolumePreservesFullScalePcmExactly() {
        val source = pcm(-32768, 32767, 0, -1)
        val output = ByteArray(source.size)
        CrvNavigationPcmGain().apply(source, 0, source.size, output, 1f)
        assertArrayEquals(source, output)
    }
    private fun pcm(vararg samples: Int): ByteArray = samples.flatMap {
        listOf(it.toByte(), (it shr 8).toByte())
    }.toByteArray()

    @Test fun quietStereoGuidanceIsRaisedWithoutMutatingSourceOrPadding() {
        val source = pcm(99, 1000, -2000, 88)
        val original = source.copyOf()
        val result = ByteArray(6) { 42 }
        CrvNavigationPcmGain().apply(source, 2, 4, result, 1.5f)
        assertArrayEquals(pcm(1500, -3000) + byteArrayOf(42, 42), result)
        assertArrayEquals(original, source)
    }

    @Test fun loudInputIsLimitedAsOneBlockAndCannotWrapOrReversePolarity() {
        val result = ByteArray(8)
        CrvNavigationPcmGain().apply(pcm(10000, -20000, 32767, -32768), 0, 8, result, 3f)
        val samples = result.toList().chunked(2).map {
            ((it[0].toInt() and 255) or (it[1].toInt() shl 8)).toShort().toInt()
        }
        assertTrue(samples[0] in 9900..10000)
        assertTrue(samples[1] in -20000..-19900)
        assertTrue(samples[2] > 32000)
        assertTrue(samples[3] < -32000)
    }

    @Test fun musicCallsAndSiriNeverGetNavigationGain() {
        assertTrue(CrvNavigationPcmGain.isNavigation(100, "guidance"))
        assertTrue(CrvNavigationPcmGain.isNavigation(101, "default"))
        for (kind in listOf("media", "telephony", "speechrecognition", "alert")) {
            assertFalse(CrvNavigationPcmGain.isNavigation(100, kind))
            assertFalse(CrvNavigationPcmGain.isNavigation(101, kind))
        }
        assertFalse(CrvNavigationPcmGain.isNavigation(100, "default"))
    }

    @Test fun invalidGainFallsBackAndMalformedPcmIsRejected() {
        val result = ByteArray(2)
        CrvNavigationPcmGain().apply(pcm(1000), 0, 2, result, Float.NaN)
        assertArrayEquals(pcm(1000), result)
        try {
            CrvNavigationPcmGain().apply(ByteArray(3), 0, 3, ByteArray(3), 2f)
            fail("Odd PCM byte count must be rejected")
        } catch (_: IllegalArgumentException) { }
    }
}
