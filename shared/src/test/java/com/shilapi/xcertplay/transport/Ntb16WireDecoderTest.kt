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

    @Test(expected = IllegalArgumentException::class)
    fun invalidNtbSignatureIsRejected() {
        Ntb16WireDecoder().append(ByteArray(28))
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
