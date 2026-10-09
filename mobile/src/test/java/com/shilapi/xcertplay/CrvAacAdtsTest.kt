package com.shilapi.xcertplay

import com.shilapi.xcertplay.media.MediaCodecSupport
import java.nio.ByteBuffer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class CrvAacAdtsTest {
    @Test
    fun directHeaderMatchesExistingAdtsFrameEncoding() {
        val payload = byteArrayOf(10, 20, 30, 40, 50)
        val expected = MediaCodecSupport.adtsFrame(payload, 48_000, 2)
        val buffer = ByteBuffer.allocate(expected.size)

        CrvAacAdts.putHeader(
            target = buffer,
            accessUnitBytes = payload.size,
            sampleRate = 48_000,
            channels = 2,
        )
        buffer.put(payload)

        assertEquals(expected.size, buffer.position())
        assertArrayEquals(expected, buffer.array())
    }
}
