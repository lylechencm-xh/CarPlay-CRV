package com.shilapi.xcertplay.airplay

/** Allocation-free RTSP header writer for the high-frequency /command event path. */
internal object AirPlayEventCommandHeader {
    private val prefix =
        "POST /command RTSP/1.0\r\n" +
            "Content-Type: application/x-apple-binary-plist\r\n" +
            "Content-Length: "
    private val prefixBytes = prefix.toByteArray(Charsets.US_ASCII)
    private val cseqBytes = "\r\nCSeq: ".toByteArray(Charsets.US_ASCII)
    private val endBytes = "\r\n\r\n".toByteArray(Charsets.US_ASCII)

    const val MAX_BYTES = 160

    fun write(
        contentLength: Int,
        cseq: Int,
        target: ByteArray,
    ): Int {
        require(contentLength >= 0)
        require(cseq >= 0)
        require(target.size >= MAX_BYTES)

        var offset = 0
        prefixBytes.copyInto(target, offset)
        offset += prefixBytes.size
        offset = writeDecimal(contentLength, target, offset)
        cseqBytes.copyInto(target, offset)
        offset += cseqBytes.size
        offset = writeDecimal(cseq, target, offset)
        endBytes.copyInto(target, offset)
        offset += endBytes.size
        return offset
    }

    private fun writeDecimal(value: Int, target: ByteArray, offset: Int): Int {
        if (value == 0) {
            target[offset] = '0'.code.toByte()
            return offset + 1
        }

        var divisor = 1
        var remaining = value
        while (remaining >= 10) {
            remaining /= 10
            divisor *= 10
        }

        var position = offset
        remaining = value
        while (divisor > 0) {
            val digit = remaining / divisor
            target[position++] = ('0'.code + digit).toByte()
            remaining %= divisor
            divisor /= 10
        }
        return position
    }
}
