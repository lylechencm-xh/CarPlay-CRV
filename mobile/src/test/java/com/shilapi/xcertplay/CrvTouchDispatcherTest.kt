package com.shilapi.xcertplay

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Test

class CrvTouchDispatcherTest {
    private val target = Any()
    private fun frame(x: Double, down0: Boolean = true, down1: Boolean = false, move: Boolean = false) =
        CrvTouchFrame(target, x, 0.5, down0, 0.7, 0.7, down1, move)

    @Test fun slideKeepsDownLatestMoveAndUpInOrder() {
        val queue = CrvTouchQueue<Any>()
        queue.offer(frame(0.0))
        for (i in 1..100) queue.offer(frame(i / 100.0, move = true))
        queue.offer(frame(1.0, down0 = false))
        assertEquals(3, queue.size)
        assertFalse(queue.poll()!!.move)
        assertEquals(1.0, queue.poll()!!.x0, 0.0)
        assertFalse(queue.poll()!!.down0)
        assertEquals(99L, queue.coalesced)
    }

    @Test fun twoFingerEdgesAndCancelCannotBeCoalesced() {
        val queue = CrvTouchQueue<Any>()
        queue.offer(frame(0.1))
        queue.offer(frame(0.2, down1 = true))
        queue.offer(frame(0.3, down1 = true, move = true))
        queue.offer(frame(0.4, down1 = true, move = true))
        queue.offer(frame(0.5, down1 = false))
        queue.offer(frame(0.6, down0 = false, down1 = false))
        assertEquals(5, queue.size)
        assertFalse(queue.poll()!!.down1)
        assertTrue(queue.poll()!!.down1)
        assertEquals(0.4, queue.poll()!!.x0, 0.0)
        assertFalse(queue.poll()!!.down1)
        assertFalse(queue.poll()!!.down0)
    }

    @Test fun differentSessionMovesCannotReplaceEachOther() {
        val queue = CrvTouchQueue<Any>()
        queue.offer(frame(0.1, move = true))
        queue.offer(frame(0.2, move = true).copy(target = Any()))
        assertEquals(2, queue.size)
    }

    @Test fun overflowDiscardsOldMoveBeforeGestureEdges() {
        val queue = CrvTouchQueue<Any>(3)
        queue.offer(frame(0.1))
        queue.offer(frame(0.2, move = true))
        queue.offer(frame(0.3, down1 = true))
        queue.offer(frame(0.4, down0 = false, down1 = false))
        assertEquals(3, queue.size)
        assertEquals(0.1, queue.poll()!!.x0, 0.0)
        assertTrue(queue.poll()!!.down1)
        assertFalse(queue.poll()!!.down0)
        assertEquals(0L, queue.resets)
    }

    @Test fun saturatedEdgesReleaseBothFingersBeforeResynchronizing() {
        val queue = CrvTouchQueue<Any>(2)
        queue.offer(frame(0.1))
        queue.offer(frame(0.2, down1 = true))
        queue.offer(frame(0.3, down1 = true))
        val release = queue.poll()!!
        assertFalse(release.down0)
        assertFalse(release.down1)
        assertTrue(queue.poll()!!.down1)
        assertEquals(1L, queue.resets)
    }

    @Test(timeout = 3000) fun blockedNetworkSenderDoesNotBlockInputAndCloseDropsQueuedWork() {
        val entered = CountDownLatch(1)
        val gate = CountDownLatch(1)
        val exited = CountDownLatch(1)
        val calls = AtomicInteger()
        val dispatcher = CrvTouchDispatcher<Any>({
            calls.incrementAndGet()
            entered.countDown()
            try { gate.await(); true } finally { exited.countDown() }
        }, {})
        try {
            assertTrue(dispatcher.offer(frame(0.1)))
            assertTrue(entered.await(1, TimeUnit.SECONDS))
            for (i in 1..100) assertTrue(dispatcher.offer(frame(i / 100.0, move = true)))
            assertTrue(dispatcher.offer(frame(1.0, down0 = false)))
            dispatcher.close()
            assertFalse(dispatcher.offer(frame(0.0)))
            gate.countDown()
            assertTrue(exited.await(1, TimeUnit.SECONDS))
            assertEquals(1, calls.get())
        } finally { dispatcher.close(); gate.countDown() }
    }
}
