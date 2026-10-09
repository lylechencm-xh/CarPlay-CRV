package com.shilapi.xcertplay

/** API17-safe RTP payload extractor used by the CR-V legacy audio renderer. */
internal object CrvLegacyRtp {
    fun payload(packet: ByteArray): ByteArray? {
        val start = payloadStart(packet)
        val end = payloadEnd(packet, start)
        return if (start < 0 || end <= start) null else packet.copyOfRange(start, end)
    }

    /** Returns the payload start offset without allocating, or -1 for malformed RTP. */
    fun payloadStart(packet: ByteArray): Int {
        if (packet.size < FIXED_HEADER_BYTES) return -1
        val first = packet[0].toInt() and 0xff
        if ((first ushr 6) != 2) return -1

        val csrcCount = first and 0x0f
        var offset = FIXED_HEADER_BYTES + csrcCount * 4
        if (offset > packet.size) return -1

        if (first and EXTENSION_FLAG != 0) {
            if (offset + EXTENSION_HEADER_BYTES > packet.size) return -1
            val extensionWords =
                ((packet[offset + 2].toInt() and 0xff) shl 8) or
                    (packet[offset + 3].toInt() and 0xff)
            offset += EXTENSION_HEADER_BYTES + extensionWords * 4
            if (offset > packet.size) return -1
        }
        return offset
    }

    /** Returns the exclusive payload end for a previously validated [start], or -1. */
    fun payloadEnd(packet: ByteArray, start: Int): Int {
        if (start < FIXED_HEADER_BYTES || start > packet.size) return -1
        val first = packet[0].toInt() and 0xff
        var end = packet.size
        if (first and PADDING_FLAG != 0) {
            val paddingBytes = packet.last().toInt() and 0xff
            if (paddingBytes == 0 || paddingBytes > end - start) return -1
            end -= paddingBytes
        }
        return if (end > start) end else -1
    }

    private const val FIXED_HEADER_BYTES = 12
    private const val EXTENSION_HEADER_BYTES = 4
    private const val EXTENSION_FLAG = 0x10
    private const val PADDING_FLAG = 0x20
}
