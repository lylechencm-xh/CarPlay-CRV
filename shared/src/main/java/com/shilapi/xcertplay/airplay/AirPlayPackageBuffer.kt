package com.shilapi.xcertplay.airplay

import java.io.EOFException
import java.io.IOException

/** One connection/thread owns this buffer. Callback data is valid only during the callback. */
internal class AirPlayPackageBuffer(private val maxPackageBytes: Int) {
    private var buffer = ByteArray(32)
    private var size = 0
    private var expected = 32

    init { require(maxPackageBytes >= 32) }

    fun append(source: ByteArray, onPackage: (ByteArray, Int) -> Unit) {
        var offset = 0
        while (offset < source.size) {
            val count = minOf(expected - size, source.size - offset)
            source.copyInto(buffer, size, offset, offset + count)
            offset += count
            size += count
            if (size < expected) continue
            if (expected == 32) {
                val declared = ((buffer[0].toInt() and 0xff) shl 24) or
                    ((buffer[1].toInt() and 0xff) shl 16) or
                    ((buffer[2].toInt() and 0xff) shl 8) or (buffer[3].toInt() and 0xff)
                if (declared < 32 || declared > maxPackageBytes) {
                    throw IOException("Invalid AirPlay package size $declared")
                }
                expected = declared
                if (expected > buffer.size) {
                    var capacity = buffer.size
                    while (capacity < expected) capacity = minOf(capacity * 2L, maxPackageBytes.toLong()).toInt()
                    buffer = buffer.copyOf(capacity)
                }
            }
            if (size == expected) {
                onPackage(buffer, size)
                size = 0
                expected = 32
            }
        }
    }

    fun finish() {
        if (size != 0) throw EOFException("Truncated AirPlay package: $size of $expected bytes")
    }
}
