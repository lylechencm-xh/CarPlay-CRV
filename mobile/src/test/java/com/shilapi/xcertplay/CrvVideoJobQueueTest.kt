package com.shilapi.xcertplay

import com.shilapi.xcertplay.airplay.VideoCodec
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CrvVideoJobQueueTest {
    @Test
    fun preservesConfigAndFrameOrderWithoutJobObjects() {
        val queue = CrvVideoJobQueue(capacity = 4, maxFrameBytes = 100)
        val holder = CrvVideoJobQueue.Holder()
        val config = byteArrayOf(9, 8, 7)
        val frame = byteArrayOf(1, 2, 3)

        queue.offerConfig(VideoCodec.H264, config)
        assertEquals(CrvVideoJobQueue.FrameOffer.ADDED, queue.offerFrame(frame, 55L))

        assertTrue(queue.poll(0, holder))
        assertEquals(CrvVideoJobQueue.TYPE_CONFIG, holder.type)
        assertEquals(VideoCodec.H264, holder.codec)
        assertArrayEquals(config, holder.data)

        assertTrue(queue.poll(0, holder))
        assertEquals(CrvVideoJobQueue.TYPE_FRAME, holder.type)
        assertArrayEquals(frame, holder.data)
        assertEquals(55L, holder.receivedNs)
        assertFalse(queue.poll(0, holder))
    }

    @Test
    fun frameLimitsUseConstantTimeQueuedByteAccounting() {
        val queue = CrvVideoJobQueue(capacity = 3, maxFrameBytes = 5)

        assertEquals(
            CrvVideoJobQueue.FrameOffer.ADDED,
            queue.offerFrame(byteArrayOf(1, 2, 3), 1L),
        )
        assertEquals(3L, queue.pendingFrameBytes())
        assertEquals(
            CrvVideoJobQueue.FrameOffer.BYTE_LIMIT,
            queue.offerFrame(byteArrayOf(4, 5, 6), 2L),
        )
        assertEquals(3L, queue.pendingFrameBytes())
    }

    @Test
    fun recoveryPreservesNewestConfigAndNewestFrameOnly() {
        val queue = CrvVideoJobQueue(capacity = 5, maxFrameBytes = 100)
        val holder = CrvVideoJobQueue.Holder()
        queue.offerConfig(VideoCodec.H264, byteArrayOf(1))
        queue.offerFrame(byteArrayOf(2), 20L)
        queue.offerConfig(VideoCodec.H264, byteArrayOf(3))
        queue.offerFrame(byteArrayOf(4), 40L)

        queue.recover(byteArrayOf(9), 90L, "overflow")

        assertTrue(queue.poll(0, holder))
        assertEquals(CrvVideoJobQueue.TYPE_CONFIG, holder.type)
        assertArrayEquals(byteArrayOf(3), holder.data)
        assertTrue(queue.poll(0, holder))
        assertEquals(CrvVideoJobQueue.TYPE_RECOVER, holder.type)
        assertEquals("overflow", holder.reason)
        assertTrue(queue.poll(0, holder))
        assertEquals(CrvVideoJobQueue.TYPE_FRAME, holder.type)
        assertArrayEquals(byteArrayOf(9), holder.data)
        assertEquals(90L, holder.receivedNs)
        assertFalse(queue.poll(0, holder))
    }

    @Test
    fun backlogStopsAtControlBoundary() {
        val queue = CrvVideoJobQueue(capacity = 6, maxFrameBytes = 100)
        queue.offerFrame(byteArrayOf(1), 10L)
        queue.offerFrame(byteArrayOf(2), 20L)
        queue.offerConfig(VideoCodec.H264, byteArrayOf(3))
        queue.offerFrame(byteArrayOf(4), 40L)

        val backlog = queue.backlogAfterCurrent()

        assertEquals(2, backlog.pendingFrames)
        assertEquals(20L, backlog.newestPendingReceivedNs)
    }

    @Test
    fun backpressuredFrameReturnsBeforeNewerFramesAndKeepsByteCount() {
        val queue = CrvVideoJobQueue(capacity = 3, maxFrameBytes = 6)
        val holder = CrvVideoJobQueue.Holder()
        queue.offerFrame(byteArrayOf(1, 2), 10L)
        queue.offerFrame(byteArrayOf(3, 4), 20L)
        assertTrue(queue.poll(0, holder))

        assertTrue(queue.offerFrameFirst(holder.data!!, holder.receivedNs))
        assertEquals(4L, queue.pendingFrameBytes())
        assertTrue(queue.poll(0, holder))
        assertEquals(10L, holder.receivedNs)
        assertTrue(queue.poll(0, holder))
        assertEquals(20L, holder.receivedNs)
        assertEquals(0L, queue.pendingFrameBytes())
    }

    @Test
    fun fullQueueRecoveryRetainsNewestFrameWithoutExceedingLimits() {
        val queue = CrvVideoJobQueue(capacity = 3, maxFrameBytes = 6)
        val holder = CrvVideoJobQueue.Holder()
        queue.offerConfig(VideoCodec.H264, byteArrayOf(1))
        queue.offerFrame(byteArrayOf(2, 3), 10L)
        queue.offerFrame(byteArrayOf(4, 5), 20L)
        assertEquals(CrvVideoJobQueue.FrameOffer.FRAME_LIMIT, queue.offerFrame(byteArrayOf(6), 30L))

        queue.recover(byteArrayOf(6), 30L, "frame limit")
        assertTrue(queue.poll(0, holder))
        assertEquals(CrvVideoJobQueue.TYPE_CONFIG, holder.type)
        assertTrue(queue.poll(0, holder))
        assertEquals(CrvVideoJobQueue.TYPE_RECOVER, holder.type)
        assertTrue(queue.poll(0, holder))
        assertEquals(CrvVideoJobQueue.TYPE_FRAME, holder.type)
        assertEquals(30L, holder.receivedNs)
        assertFalse(queue.poll(0, holder))
    }
}
