package com.shilapi.xcertplay

import org.junit.Assert.*
import org.junit.Test

class CrvPcmBatchTest {
    private fun packet(vararg payload: Int): ByteArray =
        ByteArray(12).also { it[0] = 0x80.toByte() } + payload.map { it.toByte() }.toByteArray()

    @Test fun batchesWithoutChangingInputAndKeepsStereoSampleOrder() {
        val batch = CrvPcmBatch(4, 8)
        val first = packet(0x12, 0x34, 0x56, 0x78)
        val original = first.copyOf()
        assertTrue(batch.append(first))
        assertFalse(batch.full)
        assertTrue(batch.append(packet(0x9a, 0xbc, 0xde, 0xf0)))
        assertTrue(batch.full)
        assertArrayEquals(byteArrayOf(0x34, 0x12, 0x78, 0x56, 0xbc.toByte(),
            0x9a.toByte(), 0xf0.toByte(), 0xde.toByte()), batch.bytes.copyOf(batch.size))
        assertArrayEquals(original, first)
    }

    @Test fun rejectsIncompleteChannelFramesAndReusesStorageAfterClear() {
        val batch = CrvPcmBatch(4, 4)
        assertFalse(batch.append(packet(1, 2)))
        assertFalse(batch.append(byteArrayOf(1)))
        assertEquals(0, batch.size)
        assertTrue(batch.append(packet(1, 2, 3, 4, 5, 6, 7, 8)))
        val storage = batch.bytes
        batch.clear()
        assertFalse(batch.full)
        assertTrue(batch.append(packet(9, 10, 11, 12)))
        assertSame(storage, batch.bytes)
        assertArrayEquals(byteArrayOf(10, 9, 12, 11), batch.bytes.copyOf(batch.size))
    }

    @Test fun rtpPaddingIsNeverPlayedAsPcm() {
        val padded = packet(1, 2, 3, 4, 0, 2)
        padded[0] = 0xa0.toByte()
        val batch = CrvPcmBatch(4, 4)
        assertTrue(batch.append(padded))
        assertEquals(4, batch.size)
        assertArrayEquals(byteArrayOf(2, 1, 4, 3), batch.bytes.copyOf(batch.size))
    }
}
