package com.shilapi.xcertplay

import com.shilapi.xcertplay.airplay.MediaSink
import com.shilapi.xcertplay.airplay.ScreenCodec
import com.shilapi.xcertplay.airplay.ScreenStream
import com.shilapi.xcertplay.airplay.VideoCodec
import com.shilapi.xcertplay.media.MediaCodecSupport
import java.nio.ByteBuffer
import org.junit.Assert.*
import org.junit.Test

class CrvVideoInputPathTest {
    private fun packet(vararg nals: ByteArray): ByteArray {
        val buffer = ByteBuffer.allocate(nals.sumOf { it.size + 4 })
        nals.forEach { buffer.putInt(it.size); buffer.put(it) }
        return buffer.array()
    }

    private fun listener(sink: MediaSink, current: () -> Boolean): ScreenStream.Listener {
        // Exercise the internal shared-module adapter through its JVM method.
        val method = Class.forName("com.shilapi.xcertplay.airplay.CarPlayMediaEngineKt")
            .declaredMethods.single { it.name == "currentScreenListener" }
        return method.invoke(null, 110, sink, current) as ScreenStream.Listener
    }

    @Test fun optedInPayloadIsNotMutatedAndCodecBytesMatchLegacyPath() {
        val payload = packet(byteArrayOf(0x67, 1, 2), byteArrayOf(0x65, 3, 4, 5))
        val original = payload.copyOf()
        val direct = ScreenCodec.prepareFrame(payload, true)
        assertSame(payload, direct)
        assertArrayEquals(original, payload)
        assertTrue(MediaCodecSupport.isRandomAccessPacket(direct, VideoCodec.H264))
        val target = ByteBuffer.allocateDirect(payload.size)
        assertTrue(MediaCodecSupport.putVideoInput(direct, target))
        target.flip()
        val codecBytes = ByteArray(target.remaining())
        target.get(codecBytes)
        assertArrayEquals(ScreenCodec.prepareFrame(original.copyOf(), false), codecBytes)
    }

    @Test fun largeInterframePayloadDoesNotLookLikeAnIdr() {
        val interframe = ByteArray(256 * 1024) { 0x65 }
        interframe[0] = 0x41
        val payload = packet(byteArrayOf(0x67, 1), interframe)
        assertFalse(MediaCodecSupport.isRandomAccessPacket(payload, VideoCodec.H264))
        assertEquals(payload.size, MediaCodecSupport.videoInputSize(payload))
    }

    @Test fun malformedTailIsRejectedBeforeTouchingCodecBuffer() {
        val payload = packet(byteArrayOf(0x65, 1)) + byteArrayOf(0, 0, 0, 10, 0x41)
        val direct = ScreenCodec.prepareFrame(payload, true)
        val target = ByteBuffer.allocate(payload.size)
        target.position(2)
        assertEquals(0, MediaCodecSupport.videoInputSize(direct))
        assertFalse(MediaCodecSupport.isRandomAccessPacket(direct, VideoCodec.H264))
        assertFalse(MediaCodecSupport.putVideoInput(direct, target))
        assertEquals(2, target.position())
    }

    @Test fun ordinarySinkRetainsAnnexBAndReplacedStreamStillDropsLateFrames() {
        assertFalse(listener(object : MediaSink {}, { true }).wantsLengthPrefixedFrames())
        var current = true
        val received = mutableListOf<ByteArray>()
        val sink = object : MediaSink {
            override fun acceptsLengthPrefixedVideo(type: Int) = type == 110
            override fun onVideoFrame(type: Int, naluBytes: ByteArray) { received += naluBytes }
        }
        val adapter = listener(sink) { current }
        assertTrue(adapter.wantsLengthPrefixedFrames())
        val payload = packet(byteArrayOf(0x41, 1, 2))
        adapter.onFrame(ScreenCodec.prepareFrame(payload, adapter.wantsLengthPrefixedFrames()))
        assertSame(payload, received.single())
        current = false
        adapter.onFrame(payload)
        assertEquals(1, received.size)
    }

    @Test fun alreadyAnnexBInputRemainsAcceptedByDirectPath() {
        val payload = byteArrayOf(0, 0, 0, 1, 0x65, 2, 3)
        val target = ByteBuffer.allocate(payload.size)
        assertSame(payload, ScreenCodec.prepareFrame(payload, true))
        assertTrue(MediaCodecSupport.putVideoInput(payload, target))
        assertArrayEquals(payload, target.array())
    }
}
