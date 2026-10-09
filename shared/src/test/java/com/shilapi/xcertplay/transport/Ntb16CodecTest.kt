package com.shilapi.xcertplay.transport

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class Ntb16CodecTest {
    @Test
    fun parseCanReadBlockFromLargerBuffer() {
        val frame = byteArrayOf(0x33, 0x33, 0, 0, 0, 1, 0x86.toByte(), 0xdd.toByte())
        val block = Ntb16Codec.build(frame, 7)
        val buffer = ByteArray(11) + block + ByteArray(3)

        val parsed = Ntb16Codec.parse(buffer, 11, block.size)

        assertArrayEquals(frame, parsed.single())
    }

    @Test
    fun buildIntoMatchesAllocatedBuild() {
        val frame = ByteArray(1500) { (it and 0xff).toByte() }
        val expected = Ntb16Codec.build(frame, 0x2345)
        val target = ByteArray(16 * 1024)

        val length = Ntb16Codec.buildInto(frame, 0x2345, target)

        assertEquals(expected.size, length)
        assertArrayEquals(expected, target.copyOf(length))
    }

    @Test
    fun reusableBuildKeepsOptionalUsbPadOutsideBlockLength() {
        val frame = ByteArray(484) { 0x31 }
        val target = ByteArray(1024)

        val length = Ntb16Codec.buildInto(frame, 7, target)

        assertEquals(513, length)
        assertEquals(0x00, target[8].toInt() and 0xff)
        assertEquals(0x02, target[9].toInt() and 0xff)
        assertEquals(0, target[512].toInt())
    }

    @Test
    fun largestDatagramKeepsBlockLengthWithinU16() {
        val block = Ntb16Codec.build(ByteArray(65_507), 0x1234)

        assertEquals(65_535, block.size)
        assertEquals(0xff, block[8].toInt() and 0xff)
        assertEquals(0xff, block[9].toInt() and 0xff)
    }

    @Test(expected = IllegalArgumentException::class)
    fun datagramThatWouldOverflowBlockLengthIsRejected() {
        Ntb16Codec.build(ByteArray(65_508), 0x1234)
    }
}
