package com.shilapi.xcertplay

/** Merges already available LPCM packets, with one endian conversion and no packet mutation. */
internal class CrvPcmBatch(private val frameBytes: Int, private val targetBytes: Int) {
    var bytes = ByteArray(targetBytes); private set
    var size = 0; private set
    val full: Boolean get() = size >= targetBytes
    init { require(frameBytes > 0 && frameBytes % 2 == 0 && targetBytes >= frameBytes) }
    fun clear() { size = 0 }
    fun append(rtp: ByteArray): Boolean {
        val start = CrvLegacyRtp.payloadStart(rtp)
        val end = CrvLegacyRtp.payloadEnd(rtp, start)
        if (start < 0 || end <= start || (end - start) % frameBytes != 0) return false
        val count = end - start
        if (bytes.size < size + count) bytes = bytes.copyOf(maxOf(size + count, bytes.size * 2))
        var source = start
        while (source < end) {
            bytes[size++] = rtp[source + 1]
            bytes[size++] = rtp[source]
            source += 2
        }
        return true
    }
}
