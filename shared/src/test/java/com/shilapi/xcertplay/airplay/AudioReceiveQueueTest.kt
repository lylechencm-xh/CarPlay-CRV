package com.shilapi.xcertplay.airplay

import org.junit.Assert.*
import org.junit.Test

class AudioReceiveQueueTest {
    @Test fun buffersStayOwnedUntilExplicitlyRecycled() {
        val queue = AudioReceiveQueue(4, 100)
        val packet = AudioReceiveQueue.Packet()
        val source = byteArrayOf(1, 2, 3)
        queue.offerCopy(source, 3, 0)
        source[0] = 9
        assertTrue(queue.poll(packet) { 0 })
        val first = packet.bytes!!
        assertArrayEquals(byteArrayOf(1, 2, 3), first)
        queue.offerCopy(source, 3, 0)
        assertArrayEquals(byteArrayOf(1, 2, 3), first)
        queue.recycle(packet)
        assertNull(packet.bytes)
        queue.offerCopy(byteArrayOf(4, 5, 6), 3, 0)
        assertTrue(queue.poll(packet) { 0 })
        assertNotSame(first, packet.bytes)
        queue.recycle(packet)
        assertTrue(queue.poll(packet) { 0 })
        assertSame(first, packet.bytes)
        assertArrayEquals(byteArrayOf(4, 5, 6), packet.bytes)
        queue.close()
    }

    @Test fun expiryOverflowAndCloseRaceHaveSeparateFinalCounts() {
        val queue = AudioReceiveQueue(1, 100)
        queue.offerCopy(byteArrayOf(1), 1, 0)
        queue.offerCopy(byteArrayOf(2), 1, 1) // overflow
        queue.offerCopy(byteArrayOf(3), 1, 102) // expiry
        queue.close() // cleanup queued packet
        queue.offerCopy(byteArrayOf(4), 1, 103) // receiver raced with close
        val stats = queue.stats()
        assertEquals(1L, stats.overflow)
        assertEquals(1L, stats.expired)
        assertEquals(2L, stats.cleanup)
        assertEquals(0, stats.depth)
        queue.close()
        assertEquals(stats, queue.stats())
    }
}
