package com.shilapi.xcertplay

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CrvAudioPacketQueueTest {
    @Test
    fun preservesPacketOrderWithoutAllocatingEntries() {
        val queue = CrvAudioPacketQueue(3)
        val holder = CrvAudioPacketQueue.MutablePacket()
        val first = byteArrayOf(1)
        val second = byteArrayOf(2)

        queue.offer(first, 10)
        queue.offer(second, 20)

        assertTrue(queue.poll(0, holder))
        assertArrayEquals(first, holder.rtp)
        assertEquals(10, holder.sample)
        assertTrue(queue.poll(0, holder))
        assertArrayEquals(second, holder.rtp)
        assertEquals(20, holder.sample)
        assertFalse(queue.poll(0, holder))
        assertTrue(queue.isEmpty())
    }

    @Test
    fun fullQueueDropsOldestPacketLikePreviousDeque() {
        val queue = CrvAudioPacketQueue(2)
        val holder = CrvAudioPacketQueue.MutablePacket()

        queue.offer(byteArrayOf(1), 1)
        queue.offer(byteArrayOf(2), 2)
        queue.offer(byteArrayOf(3), 3)

        assertEquals(2, queue.size())
        assertTrue(queue.poll(0, holder))
        assertEquals(2, holder.sample)
        assertTrue(queue.poll(0, holder))
        assertEquals(3, holder.sample)
    }
}
