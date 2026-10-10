package com.shilapi.xcertplay.airplay

import java.io.EOFException
import java.io.InputStream

/** Reads exactly one authenticated frame at a time without retaining/copying TCP tails. */
internal class AirPlayDataStreamReader(
    private val open: (Long, ByteArray, ByteArray, Int) -> ByteArray,
) {
    private val header = ByteArray(2)
    private var sealed = ByteArray(4096)
    private var counter = 0L

    fun read(input: InputStream): ByteArray? {
        if (!readFully(input, header, 2, allowEof = true)) return null
        val length = (header[0].toInt() and 0xff) or ((header[1].toInt() and 0xff) shl 8)
        val sealedLength = length + 16
        if (sealedLength > sealed.size) sealed = ByteArray(sealedLength)
        readFully(input, sealed, sealedLength, allowEof = false)
        val plaintext = open(counter, header, sealed, sealedLength)
        counter++ // Authentication failure must not advance the nonce.
        return plaintext
    }

    private fun readFully(input: InputStream, target: ByteArray, length: Int, allowEof: Boolean): Boolean {
        var offset = 0
        while (offset < length) {
            val count = input.read(target, offset, length - offset)
            if (count < 0) {
                if (allowEof && offset == 0) return false
                throw EOFException("Truncated AirPlay data frame")
            }
            if (count == 0) {
                val byte = input.read()
                if (byte < 0) throw EOFException("Truncated AirPlay data frame")
                target[offset++] = byte.toByte()
            } else offset += count
        }
        return true
    }
}
