package com.shilapi.xcertplay.media

import com.shilapi.xcertplay.airplay.VideoCodec
import java.nio.ByteBuffer
import org.junit.Assert.*
import org.junit.Test

class VideoInputBufferTest {
    private fun packet(vararg nals: ByteArray): ByteArray {
        val output = ByteBuffer.allocate(nals.sumOf { it.size + 4 })
        nals.forEach { output.putInt(it.size); output.put(it) }
        return output.array()
    }

    @Test fun directBufferMatchesConversionWithoutMutatingPacket() {
        val input = packet(byteArrayOf(0x67, 1, 2), byteArrayOf(0x65, 3, 4, 5))
        val original = input.copyOf()
        val target = ByteBuffer.allocateDirect(input.size + 8)
        target.position(3)
        assertEquals(input.size, MediaCodecSupport.videoInputSize(input))
        assertTrue(MediaCodecSupport.putVideoInput(input, target))
        assertEquals(input.size + 3, target.position())
        target.flip(); target.position(3)
        val converted = ByteArray(input.size)
        target.get(converted)
        assertArrayEquals(MediaCodecSupport.toAnnexB(input), converted)
        assertArrayEquals(original, input)
        assertTrue(MediaCodecSupport.isRandomAccessPacket(input, VideoCodec.H264))
    }

    @Test fun nalLengthOf300IsNotMistakenForAThreeByteStartCode() {
        val nal = ByteArray(300) { 0x31 }
        nal[0] = 0x65
        val input = packet(nal)
        val target = ByteBuffer.allocate(input.size)
        assertTrue(MediaCodecSupport.putVideoInput(input, target))
        assertArrayEquals(MediaCodecSupport.toAnnexB(input), target.array())
        assertTrue(MediaCodecSupport.isRandomAccessPacket(input, VideoCodec.H264))
    }

    @Test fun malformedTailAndSmallBufferAreRejectedBeforeAnyWrite() {
        val valid = packet(byteArrayOf(0x65, 1, 2))
        for (input in listOf(valid + byteArrayOf(0, 1), byteArrayOf(0, 0, 0, 0),
            byteArrayOf(0x7f, -1, -1, -1, 1))) {
            val target = ByteBuffer.allocate(30)
            target.position(2)
            assertFalse(MediaCodecSupport.putVideoInput(input, target))
            assertEquals(2, target.position())
            assertFalse(MediaCodecSupport.isRandomAccessPacket(input, VideoCodec.H264))
        }
        val small = ByteBuffer.allocate(valid.size - 1)
        assertFalse(MediaCodecSupport.putVideoInput(valid, small))
        assertEquals(0, small.position())
    }

    @Test fun existingAnnexBAndHevcRandomAccessAreAccepted() {
        val annexB = byteArrayOf(0, 0, 0, 1, 0x65, 8, 9)
        val target = ByteBuffer.allocate(annexB.size)
        assertTrue(MediaCodecSupport.putVideoInput(annexB, target))
        assertArrayEquals(annexB, target.array())
        assertTrue(MediaCodecSupport.isRandomAccessPacket(annexB, VideoCodec.H264))
        assertTrue(MediaCodecSupport.isRandomAccessPacket(packet(byteArrayOf(0x26, 1)), VideoCodec.H265))
        assertFalse(MediaCodecSupport.isRandomAccessPacket(packet(byteArrayOf(0x02, 1)), VideoCodec.H265))
        assertFalse(MediaCodecSupport.isRandomAccessPacket(packet(byteArrayOf(0x41, 2)), VideoCodec.H264))
    }
}
