package com.shilapi.xcertplay

import com.shilapi.xcertplay.airplay.BplistCodec
import com.shilapi.xcertplay.airplay.RtspMessage
import org.junit.Assert.*
import org.junit.Test

class CrvProtocolBoundsTest {
    private fun rejected(action: () -> Unit) {
        try { action(); fail("Malformed input was accepted") } catch (_: IllegalArgumentException) { }
    }

    @Test fun extremeNegativeAndInvalidLengthsAreRejectedWithoutAllocation() {
        for (length in listOf("2147483647", "-1", "9999999999999999999999", "garbage")) {
            rejected { RtspMessage.parseMessages("POST /info RTSP/1.0\r\nContent-Length: $length\r\n\r\n".toByteArray()) }
        }
    }
    @Test fun conflictingLengthsAndOversizedIncompleteHeadersAreRejected() {
        rejected { RtspMessage.parseMessages("POST /info RTSP/1.0\r\nContent-Length: 1\r\nContent-Length: 2\r\n\r\n".toByteArray()) }
        rejected { RtspMessage.parseMessages(ByteArray(RtspMessage.MAX_HEADER_BYTES + 1) { 65 }) }
        rejected { RtspMessage.checkAppend(RtspMessage.MAX_BUFFER_BYTES, 1) }
        rejected { RtspMessage.checkAppend(Int.MAX_VALUE, Int.MAX_VALUE) }
    }
    @Test fun pipelinedMessagesAndPartialBodiesRemainSupported() {
        val first = "POST /info RTSP/1.0\r\nContent-Length: 3\r\n\r\nabc"
        val second = "POST /info RTSP/1.0\r\nContent-Length: 2\r\n\r\nx"
        val partial = RtspMessage.parseMessages((first + second).toByteArray())
        assertEquals(1, partial.messages.size)
        assertArrayEquals("abc".toByteArray(), partial.messages.single().body)
        assertArrayEquals(second.toByteArray(), partial.rest)
        assertEquals("xy", String(RtspMessage.parseMessages(partial.rest + 'y'.code.toByte()).messages.single().body))
    }
    @Test fun boundarySizedBodyDeclarationWaitsForData() {
        val request = "POST /info RTSP/1.0\r\nContent-Length: ${RtspMessage.MAX_BODY_BYTES}\r\n\r\n".toByteArray()
        assertArrayEquals(request, RtspMessage.parseMessages(request).rest)
    }
    @Test fun tinyCyclicPlistFailsWithValidationError() {
        val bytes = ByteArray(43)
        "bplist00".toByteArray().copyInto(bytes)
        bytes[8] = 0xa1.toByte(); bytes[10] = 8
        bytes[17] = 1; bytes[18] = 1; bytes[26] = 1; bytes[42] = 10
        rejected { BplistCodec.decode(bytes) }
    }
    @Test fun paddedUnsignedConnectionIdentifierRemainsCompatible() {
        val original = BplistCodec.encode(java.math.BigInteger("18446744073709551615"))
        val bytes = original.copyOfRange(0, 9) + ByteArray(8) + original.copyOfRange(9, original.size)
        bytes[8] = 0x14
        bytes[bytes.lastIndex] = (original.last().toInt() + 8).toByte()
        assertEquals(-1L, BplistCodec.decode(bytes))
    }
    @Test fun invalidOffsetsObjectCountsAndTruncatedValuesAreRejected() {
        val valid = BplistCodec.encode(mapOf("key" to "value"))
        val hugeCount = valid.copyOf().apply { this[size - 24] = 0x7f }
        rejected { BplistCodec.decode(hugeCount) }
        val badTable = valid.copyOf().apply { this[size - 1] = 0x7f }
        rejected { BplistCodec.decode(badTable) }
        val badValue = valid.copyOf().apply { this[8] = 0x4f; this[9] = 0x10; this[10] = 0x7f }
        rejected { BplistCodec.decode(badValue) }
    }
    @Test fun excessiveDepthIsRejectedButNormalNestedValuesRoundTrip() {
        var nested: Any = "leaf"
        repeat(70) { nested = listOf(nested) }
        rejected { BplistCodec.decode(BplistCodec.encode(nested)) }
        val normal = mapOf("stream" to listOf(100L, true, "media"), "data" to byteArrayOf(1, 2))
        val result = BplistCodec.decode(BplistCodec.encode(normal)) as Map<*, *>
        assertEquals(normal["stream"], result["stream"])
        assertArrayEquals(byteArrayOf(1, 2), result["data"] as ByteArray)
    }
    @Test fun sharedReferencesAreNotMistakenForCycles() {
        val bytes = ByteArray(49)
        "bplist00".toByteArray().copyInto(bytes)
        bytes[8] = 0xa2.toByte(); bytes[9] = 1; bytes[10] = 1
        bytes[11] = 0x53; "abc".toByteArray().copyInto(bytes, 12)
        bytes[15] = 8; bytes[16] = 11
        bytes[23] = 1; bytes[24] = 1; bytes[32] = 2; bytes[48] = 15
        assertEquals(listOf("abc", "abc"), BplistCodec.decode(bytes))
    }
    @Test fun tinyAcyclicGraphCannotExpandIntoAnUnboundedTree() {
        val levels = 30
        val bodyEnd = 8 + levels * 3 + 5
        val trailer = bodyEnd + levels + 1
        val bytes = ByteArray(trailer + 32)
        "bplist00".toByteArray().copyInto(bytes)
        for (level in 0 until levels) {
            val position = 8 + level * 3
            bytes[position] = 0xa2.toByte()
            bytes[position + 1] = (level + 1).toByte()
            bytes[position + 2] = (level + 1).toByte()
            bytes[bodyEnd + level] = position.toByte()
        }
        bytes[8 + levels * 3] = 0x54
        "leaf".toByteArray().copyInto(bytes, 9 + levels * 3)
        bytes[bodyEnd + levels] = (8 + levels * 3).toByte()
        bytes[trailer + 6] = 1; bytes[trailer + 7] = 1
        bytes[trailer + 15] = (levels + 1).toByte()
        bytes[trailer + 31] = bodyEnd.toByte()
        rejected { BplistCodec.decode(bytes) }
    }
}
