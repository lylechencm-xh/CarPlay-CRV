package com.shilapi.xcertplay

import io.github.jaredmdobson.concentus.OpusApplication
import io.github.jaredmdobson.concentus.OpusEncoder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CrvSoftwareOpusDecoderTest {
    @Test fun decodesTwentyMillisecondMonoPacket() {
        val encoder = OpusEncoder(48_000, 1, OpusApplication.OPUS_APPLICATION_VOIP)
        val samples = ShortArray(960) { index -> if (index % 2 == 0) 500 else -500 }
        val packet = ByteArray(1500)
        val packetSize = encoder.encode(samples, 0, samples.size, packet, 0, packet.size)
        assertTrue(packetSize > 0)

        val decoder = CrvSoftwareOpusDecoder(48_000, 1)
        val bytes = decoder.decode(packet.copyOf(packetSize))
        assertEquals(960 * 2, bytes)
        assertTrue(bytes <= decoder.pcm.size)
    }

    @Test
    fun decodesPacketSliceWithoutCopy() {
        val encoder = OpusEncoder(48_000, 1, OpusApplication.OPUS_APPLICATION_VOIP)
        val samples = ShortArray(960) { index -> if (index % 2 == 0) 400 else -400 }
        val encoded = ByteArray(1500)
        val packetSize = encoder.encode(samples, 0, samples.size, encoded, 0, encoded.size)
        val wrapped = ByteArray(packetSize + 8)
        encoded.copyInto(wrapped, 4, 0, packetSize)

        val decoder = CrvSoftwareOpusDecoder(48_000, 1)
        val bytes = decoder.decode(wrapped, 4, packetSize)

        assertEquals(960 * 2, bytes)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsEmptyPackets() {
        CrvSoftwareOpusDecoder(48_000, 1).decode(byteArrayOf())
    }
}
