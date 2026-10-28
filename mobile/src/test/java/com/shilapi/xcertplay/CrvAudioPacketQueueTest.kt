package com.shilapi.xcertplay

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CrvAudioPacketQueueTest {
    @Test fun dropReasonsAreExclusiveAndSumToTotalAcrossAllLimits() {
        fun packet(size: Int) = ByteArray(12 + size).also { it[0] = 0x80.toByte() }
        val queue = CrvAudioPacketQueue(2, maxAgeNs = 100, maxPayloadBytes = 10)
        queue.offer(packet(4), 1, 1)
        queue.offer(packet(4), 2, 2)
        queue.offer(packet(8), 3, 3) // capacity first, then byte budget
        queue.offer(packet(11), 4, 4) // oversized
        queue.offer(packet(1), 5, 200, receivedAtNs = 1) // expire queued and arriving
        val stats = queue.stats(200)
        assertEquals(1L, stats.capacityDropped)
        assertEquals(1L, stats.byteBudgetDropped)
        assertEquals(1L, stats.oversized)
        assertEquals(2L, stats.expired)
        assertEquals(5L, stats.dropped)
        assertEquals(stats.dropped,
            stats.expired + stats.capacityDropped + stats.byteBudgetDropped + stats.oversized)
    }

    @Test fun tracksActualPayloadBytesAndAgeAcrossOverflowAndPoll() {
        val queue = CrvAudioPacketQueue(2)
        val holder = CrvAudioPacketQueue.MutablePacket()
        fun packet(size: Int) = ByteArray(12 + size).also { it[0] = 0x80.toByte() }
        queue.offer(packet(4), 1, 100L)
        queue.offer(packet(8), 2, 200L)
        assertEquals(12L, queue.queuedPayloadBytes())
        assertEquals(200L, queue.stats(300L).oldestAgeNs)
        queue.offer(packet(12), 3, 300L)
        assertEquals(20L, queue.stats(400L).payloadBytes)
        assertEquals(200L, queue.stats(400L).oldestAgeNs)
        assertTrue(queue.poll(0L, holder))
        assertEquals(12L, queue.queuedPayloadBytes())
        assertTrue(queue.poll(0L, holder))
        assertEquals(0L, queue.stats(500L).payloadBytes)
        assertEquals(0L, queue.stats(500L).oldestAgeNs)
    }
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
        assertEquals(1L, queue.stats().dropped)
        assertEquals(2, queue.stats().highWater)
        assertTrue(queue.poll(0, holder))
        assertEquals(2, holder.sample)
        assertTrue(queue.poll(0, holder))
        assertEquals(3, holder.sample)
        assertEquals(0, queue.stats().depth)
        assertEquals(1L, queue.stats().dropped)
    }
}
