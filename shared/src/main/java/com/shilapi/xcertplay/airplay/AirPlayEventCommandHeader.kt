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
        // eventCseq is an Int and can wrap after a very long-lived session. Preserve the
        // legacy RTSP string representation even for negative values after overflow.
        require(target.size >= MAX_BYTES)

        var offset = 0
        prefixBytes.copyInto(target, offset)
        offset += prefixBytes.size
        offset = writeDecimal(contentLength.toLong(), target, offset)
        cseqBytes.copyInto(target, offset)
        offset += cseqBytes.size
        offset = writeDecimal(cseq.toLong(), target, offset)
        endBytes.copyInto(target, offset)
        offset += endBytes.size
        return offset
    }

    private fun writeDecimal(value: Long, target: ByteArray, offset: Int): Int {
        var remaining = value
        var position = offset
        if (remaining < 0L) {
            target[position++] = '-'.code.toByte()
            remaining = -remaining
        }
        if (remaining == 0L) {
            target[position] = '0'.code.toByte()
            return position + 1
        }

        var divisor = 1L
        var digits = remaining
        while (digits >= 10L) {
            digits /= 10L
            divisor *= 10L
        }

        while (divisor > 0L) {
            val digit = (remaining / divisor).toInt()
            target[position++] = ('0'.code + digit).toByte()
            remaining %= divisor
            divisor /= 10L
        }
        return position
    }
}
