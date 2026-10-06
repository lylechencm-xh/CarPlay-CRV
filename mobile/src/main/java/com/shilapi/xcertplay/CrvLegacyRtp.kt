package com.shilapi.xcertplay

/** API17-safe RTP payload extractor used by the CR-V legacy audio renderer. */
internal object CrvLegacyRtp {
    fun payload(packet: ByteArray): ByteArray? {
        if (packet.size < FIXED_HEADER_BYTES) return null
        val first = packet[0].toInt() and 0xff
        if ((first ushr 6) != 2) return null

        val csrcCount = first and 0x0f
        var offset = FIXED_HEADER_BYTES + csrcCount * 4
        if (offset > packet.size) return null

        if (first and EXTENSION_FLAG != 0) {
            if (offset + EXTENSION_HEADER_BYTES > packet.size) return null
            val extensionWords =
                ((packet[offset + 2].toInt() and 0xff) shl 8) or
                    (packet[offset + 3].toInt() and 0xff)
            offset += EXTENSION_HEADER_BYTES + extensionWords * 4
            if (offset > packet.size) return null
        }

        var end = packet.size
        if (first and PADDING_FLAG != 0) {
            val paddingBytes = packet.last().toInt() and 0xff
            if (paddingBytes == 0 || paddingBytes > end - offset) return null
            end -= paddingBytes
        }
        if (end <= offset) return null
        return packet.copyOfRange(offset, end)
    }

    private const val FIXED_HEADER_BYTES = 12
    private const val EXTENSION_HEADER_BYTES = 4
    private const val EXTENSION_FLAG = 0x10
    private const val PADDING_FLAG = 0x20
}
