package com.shilapi.xcertplay.transport

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Ntb16WireDecoderTest {
    private val frame = ByteArray(484) { 0x31.toByte() }
    private val exactPacketBlock = Ntb16Codec.build(frame, 7)

    @Test fun exactPacketBlockWithoutPadIsCompleteImmediately() {
        val decoder = Ntb16WireDecoder()
        assertEquals(513, exactPacketBlock.size)
        val blocks = decoder.append(exactPacketBlock.copyOfRange(0, 512))
        assertEquals(1, blocks.size)
        assertArrayEquals(frame, Ntb16Codec.parse(blocks.single()).single())
    }

    @Test fun zeroPadCanArriveInANewTransferBeforeTheNextBlock() {
        val decoder = Ntb16WireDecoder()
        val next = Ntb16Codec.build(byteArrayOf(1, 2, 3), 8)
        assertEquals(1, decoder.append(exactPacketBlock.copyOfRange(0, 512)).size)
        assertTrue(decoder.append(byteArrayOf(0)).isEmpty())
        val parsed = decoder.append(next)
        assertArrayEquals(byteArrayOf(1, 2, 3), Ntb16Codec.parse(parsed.single()).single())
    }

    @Test fun consecutiveBlocksWithoutOptionalPadAreNotMisaligned() {
        val decoder = Ntb16WireDecoder()
        val next = Ntb16Codec.build(byteArrayOf(4, 5), 8)
        val blocks = decoder.append(exactPacketBlock.copyOfRange(0, 512) + next)
        assertEquals(2, blocks.size)
        assertArrayEquals(frame, Ntb16Codec.parse(blocks[0]).single())
        assertArrayEquals(byteArrayOf(4, 5), Ntb16Codec.parse(blocks[1]).single())
    }

    @Test fun ntbWithIncludedPadInBlockLengthIsAccepted() {
        val decoder = Ntb16WireDecoder()
        val linuxStyle = exactPacketBlock.copyOf()
        linuxStyle[8] = 0x01
        linuxStyle[9] = 0x02 // 513 bytes including the short packet byte
        val blocks = decoder.append(linuxStyle)
        assertArrayEquals(frame, Ntb16Codec.parse(blocks.single()).single())
    }

    @Test fun fragmentedNtbsAreReassembledAcrossBulkReads() {
        val decoder = Ntb16WireDecoder()
        val data = Ntb16Codec.build(byteArrayOf(7, 8, 9), 12)
        assertTrue(decoder.append(data.copyOfRange(0, 5)).isEmpty())
        assertTrue(decoder.append(data.copyOfRange(5, 22)).isEmpty())
        val blocks = decoder.append(data.copyOfRange(22, data.size))
        assertArrayEquals(byteArrayOf(7, 8, 9), Ntb16Codec.parse(blocks.single()).single())
    }

    @Test fun appendFramesSkipsCompleteNtbCopyAndKeepsFragmentReassembly() {
        val decoder = Ntb16WireDecoder()
        val firstFrame = byteArrayOf(7, 8, 9)
        val first = Ntb16Codec.build(firstFrame, 12)
        val secondFrame = byteArrayOf(4, 5, 6, 7)
        val second = Ntb16Codec.build(secondFrame, 13)

        assertTrue(decoder.appendFrames(first.copyOfRange(0, 9)).isEmpty())
        val frames = decoder.appendFrames(first.copyOfRange(9, first.size) + second)

        assertEquals(2, frames.size)
        assertArrayEquals(firstFrame, frames[0])
        assertArrayEquals(secondFrame, frames[1])
    }

    @Test fun garbageBeforeHeaderIsSkippedAcrossUsbReads() {
        val decoder = Ntb16WireDecoder()
        val data = Ntb16Codec.build(byteArrayOf(3, 4, 5), 2)
        assertTrue(decoder.append(ByteArray(28) + data.copyOfRange(0, 7)).isEmpty())
        val blocks = decoder.append(data.copyOfRange(7, data.size))
        assertArrayEquals(byteArrayOf(3, 4, 5), Ntb16Codec.parse(blocks.single()).single())
        assertTrue(decoder.resynchronizations > 0)
        assertEquals(28L, decoder.discardedBytes)
    }

    @Test fun resyncBudgetRecoversAfterWindowAndReportsDiscardedBytes() {
        var now = 0L
        var reportedBytes = 0L
        val decoder = Ntb16WireDecoder(clockNanos = { now }, onResync = { bytes, _ -> reportedBytes += bytes })
        repeat(16) { decoder.append(ByteArray(16) { 0x55 }) }
        now += 10_000_000_000L
        decoder.append(ByteArray(16) { 0x55 })
        assertEquals(17L, decoder.resynchronizations)
        assertEquals(reportedBytes, decoder.discardedBytes)
    }

    @Test fun alternateEndpointPacketSizeSkipsOptionalPad() {
        val decoder = Ntb16WireDecoder(64)
        val first = Ntb16Codec.build(ByteArray(36) { 1 }, 1)
        val second = Ntb16Codec.build(byteArrayOf(9), 2)
        val blocks = decoder.append(first.copyOfRange(0, 64) + byteArrayOf(0) + second)
        assertEquals(2, blocks.size)
        assertArrayEquals(byteArrayOf(9), Ntb16Codec.parse(blocks[1]).single())
    }

    @Test(expected = IllegalArgumentException::class)
    fun repeatedCorruptionEventuallyFails() {
        val decoder = Ntb16WireDecoder()
        repeat(17) { decoder.append(ByteArray(16) { 0x55 }) }
    }

    @Test fun legacyBulkTransferChunksNeverExceed16KiB() {
        var remaining = 65535
        var transactions = 0
        while (remaining > 0) {
            val chunk = NcmLegacyUsbBulk.chunkSize(remaining)
            assertTrue(chunk in 1..16384)
            remaining -= chunk
            transactions++
        }
        assertEquals(4, transactions)
        assertEquals(16384, NcmLegacyUsbBulk.chunkSize(16384))
        assertEquals(1, NcmLegacyUsbBulk.chunkSize(1))
    }
}
