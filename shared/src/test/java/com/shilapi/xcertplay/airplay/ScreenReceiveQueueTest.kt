package com.shilapi.xcertplay.airplay

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class ScreenReceiveQueueTest {
    @Test fun orderedOwnedBuffersAndNonceSurviveProducerReuse() {
        val queue = ScreenReceiveQueue(3, 128)
        val header = ByteArray(128); val body = byteArrayOf(1, 2, 3)
        assertTrue(queue.offer(header, body, 3, 10L, 7L))
        header[4] = 1; body[0] = 9
        assertTrue(queue.offer(header, body, 3, 20L, 8L))
        queue.finish()
        val first = queue.take()!!
        assertEquals(0, first.header[4].toInt())
        assertArrayEquals(byteArrayOf(1, 2, 3), first.body)
        assertEquals(7L, first.nonce)
        assertTrue(queue.hasNewer(first.arrivalNs))
        queue.recycle(first)
        val second = queue.take()!!
        assertEquals(1, second.header[4].toInt())
        assertEquals(9, second.body[0].toInt())
        assertEquals(8L, second.nonce)
        assertNull(queue.take())
    }

    @Test fun closeUnblocksProducerWaitingForCapacity() {
        val queue = ScreenReceiveQueue(1, 4)
        val header = ByteArray(128)
        queue.offer(header, byteArrayOf(1), 1, 1L, 0L)
        val entered = CountDownLatch(1); val done = CountDownLatch(1)
        Thread {
            entered.countDown()
            assertFalse(queue.offer(header, byteArrayOf(2), 1, 2L, 1L))
            done.countDown()
        }.apply { isDaemon = true; start() }
        assertTrue(entered.await(1, TimeUnit.SECONDS))
        queue.close()
        assertTrue(done.await(1, TimeUnit.SECONDS))
        assertNull(queue.take())
    }

    @Test fun byteBudgetBackpressuresWithoutDroppingFramesAndAllowsSingleLargePacket() {
        val queue = ScreenReceiveQueue(3, 4)
        val header = ByteArray(128)
        queue.offer(header, ByteArray(4), 4, 1L, 0L)
        val started = CountDownLatch(1); val done = CountDownLatch(1)
        Thread {
            started.countDown()
            assertTrue(queue.offer(header, ByteArray(5), 5, 2L, 1L))
            done.countDown()
        }.apply { isDaemon = true; start() }
        assertTrue(started.await(1, TimeUnit.SECONDS))
        assertFalse(done.await(30, TimeUnit.MILLISECONDS))
        assertEquals(0L, queue.take()!!.nonce)
        assertTrue(done.await(1, TimeUnit.SECONDS))
        assertEquals(1L, queue.take()!!.nonce)
        queue.close()
    }

    @Test fun boundedHistogramReportsDistributionAndResets() {
        val histogram = LatencyHistogram()
        repeat(90) { histogram.record(10_000_000L) }
        repeat(10) { histogram.record(100_000_000L) }
        assertEquals(15L, histogram.percentileMs(90))
        assertEquals(105L, histogram.percentileMs(99))
        histogram.reset()
        assertEquals(0L, histogram.percentileMs(99))
    }
}
