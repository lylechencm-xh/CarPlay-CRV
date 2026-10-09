package com.shilapi.xcertplay

import com.shilapi.xcertplay.media.MediaCodecSupport
import java.nio.ByteBuffer

/** Writes one AAC-LC ADTS frame directly into a MediaCodec input buffer. */
internal object CrvAacAdts {
    const val HEADER_BYTES = 7

    fun putHeader(
        target: ByteBuffer,
        accessUnitBytes: Int,
        sampleRate: Int,
        channels: Int,
    ) {
        require(accessUnitBytes >= 0)
        val frequencyIndex = MediaCodecSupport.aacFrequencyIndex(sampleRate)
        val channelConfig = channels.coerceIn(1, 7)
        val frameLength = accessUnitBytes + HEADER_BYTES

        target.put(0xff.toByte())
        target.put(0xf1.toByte())
        target.put(((1 shl 6) or (frequencyIndex shl 2) or (channelConfig ushr 2)).toByte())
        target.put((((channelConfig and 0x3) shl 6) or (frameLength ushr 11)).toByte())
        target.put(((frameLength ushr 3) and 0xff).toByte())
        target.put((((frameLength and 0x7) shl 5) or 0x1f).toByte())
        target.put(0xfc.toByte())
    }
}
