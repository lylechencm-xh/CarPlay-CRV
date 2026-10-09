package com.shilapi.xcertplay.transport

/**
 * Minimal NTB16 codec for the iPhone NCM data link.
 *
 * Only the 16-bit transfer-block layout used by LIVI's `iap2-usbmux/src/ntb.rs` is implemented:
 * one NTH16 header, one NDP16 table with one datagram, and the USB short-packet pad byte. There
 * is no NTB32, alignment, CRC, or NCM control-plane handling here.
 */
object Ntb16Codec {
    const val NTH16_SIG = 0x484d434e
    const val NDP16_SIG = 0x304d434e

    private const val NTH_LENGTH = 12
    private const val NDP_LENGTH = 16
    private const val DATAGRAM_INDEX = 28
    private const val USB_PACKET_SIZE = 512

    /** Wraps one Ethernet frame in one NTB16 block, padding an exact 512-byte boundary. */
    fun build(frame: ByteArray, sequence: Int): ByteArray {
        val output = ByteArray(encodedLength(frame.size))
        val length = buildInto(frame, sequence, output)
        return if (length == output.size) output else output.copyOf(length)
    }

    /** Total USB transfer bytes including the optional short-packet pad byte. */
    fun encodedLength(frameSize: Int): Int {
        require(frameSize in 1..MAX_DATAGRAM_BYTES) { "frame exceeds the NTB16 length field" }
        val blockLength = DATAGRAM_INDEX + frameSize
        return blockLength + if (blockLength % USB_PACKET_SIZE == 0) 1 else 0
    }

    /**
     * Writes one NTB16 transfer into [target] and returns the valid transfer length.
     * The NTH wBlockLength intentionally excludes the optional USB pad byte.
     */
    fun buildInto(frame: ByteArray, sequence: Int, target: ByteArray): Int {
        require(frame.isNotEmpty()) { "frame must not be empty" }
        require(frame.size <= MAX_DATAGRAM_BYTES) { "frame exceeds the NTB16 length field" }
        require(sequence in 0..0xffff) { "sequence must fit in u16" }
        val transferLength = encodedLength(frame.size)
        require(target.size >= transferLength) { "target is too small for NTB16 transfer" }

        val blockLength = DATAGRAM_INDEX + frame.size
        putU32(target, 0, NTH16_SIG)
        putU16(target, 4, NTH_LENGTH)
        putU16(target, 6, sequence)
        putU16(target, 8, blockLength)
        putU16(target, 10, NTH_LENGTH)

        putU32(target, 12, NDP16_SIG)
        putU16(target, 16, NDP_LENGTH)
        putU16(target, 18, 0)
        putU16(target, 20, DATAGRAM_INDEX)
        putU16(target, 22, frame.size)
        putU16(target, 24, 0)
        putU16(target, 26, 0)

        frame.copyInto(target, DATAGRAM_INDEX)
        if (transferLength > blockLength) target[blockLength] = 0
        return transferLength
    }

    /**
     * Extracts the Ethernet frames carried by one NTB16 block.
     *
     * Wire garbage returns an empty list instead of throwing; malformed entries are skipped where
     * a bounds check fails. A step guard stops a malformed NDP pointer chain from cycling.
     */
    fun parse(block: ByteArray): List<ByteArray> = parse(block, 0, block.size)

    /** Parses one NTB16 block from [offset] without copying the containing buffer. */
    fun parse(block: ByteArray, offset: Int, length: Int): List<ByteArray> {
        require(offset >= 0 && length >= 0 && offset <= block.size - length) {
            "NTB16 range is outside the source buffer"
        }
        val end = offset + length
        if (length < NTH_LENGTH || readU32(block, offset) != NTH16_SIG) return emptyList()

        val datagrams = ArrayList<ByteArray>(1)
        var ndpOffset = offset + readU16(block, offset + 10)
        var hops = 0
        val maxHops = maxOf(1, length / 4)
        while (ndpOffset != 0 && hops < maxHops) {
            if (ndpOffset < offset || ndpOffset + 12 > end) break
            if (readU32(block, ndpOffset) and 0x00ffffff != NDP16_SIG and 0x00ffffff) break
            val ndpLength = readU16(block, ndpOffset + 4)
            val nextNdp = readU16(block, ndpOffset + 6)
            var entry = ndpOffset + 8
            val ndpEnd = minOf(ndpOffset + ndpLength, end)
            while (entry + 4 <= ndpEnd) {
                val datagramIndex = readU16(block, entry)
                val datagramLength = readU16(block, entry + 2)
                if (datagramIndex == 0 || datagramLength == 0) break
                val datagramStart = offset + datagramIndex
                val datagramEnd = datagramStart + datagramLength
                if (datagramStart >= offset && datagramStart <= end && datagramEnd <= end) {
                    datagrams.add(block.copyOfRange(datagramStart, datagramEnd))
                }
                entry += 4
            }
            ndpOffset = if (nextNdp == 0) 0 else offset + nextNdp
            hops++
        }
        return datagrams
    }

    private fun putU16(target: ByteArray, offset: Int, value: Int) {
        target[offset] = value.toByte()
        target[offset + 1] = (value ushr 8).toByte()
    }

    private fun putU32(target: ByteArray, offset: Int, value: Int) {
        target[offset] = value.toByte()
        target[offset + 1] = (value ushr 8).toByte()
        target[offset + 2] = (value ushr 16).toByte()
        target[offset + 3] = (value ushr 24).toByte()
    }

    private fun readU16(source: ByteArray, offset: Int): Int =
        (source[offset].toInt() and 0xff) or ((source[offset + 1].toInt() and 0xff) shl 8)

    private fun readU32(source: ByteArray, offset: Int): Int =
        (source[offset].toInt() and 0xff) or
            ((source[offset + 1].toInt() and 0xff) shl 8) or
            ((source[offset + 2].toInt() and 0xff) shl 16) or
            ((source[offset + 3].toInt() and 0xff) shl 24)

    private const val MAX_DATAGRAM_BYTES = 0xffff - DATAGRAM_INDEX
}
