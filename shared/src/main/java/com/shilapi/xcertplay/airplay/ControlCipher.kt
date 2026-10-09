package com.shilapi.xcertplay.airplay

/**
 * ChaCha20-Poly1305 framing for the CarPlay control channel.
 *
 * Each frame is [2-byte little-endian ciphertext length][ciphertext][16-byte tag]; the length
 * header is the AEAD associated data and each direction uses an 8-byte little-endian nonce
 * counter plus its own key.
 */
class ControlCipher(private val readKey: ByteArray, private val writeKey: ByteArray) {
    data class Decrypted(val data: ByteArray, val rest: ByteArray)

    private var readCounter = 0L
    private var writeCounter = 0L
    private val writeSealer = AirPlayChaChaSealer(writeKey)
    private val writeNonce = ByteArray(12)
    private val writeHeader = ByteArray(HEADER_SIZE)

    fun decrypt(buffer: ByteArray): Decrypted {
        val output = ArrayList<ByteArray>()
        var offset = 0
        while (buffer.size - offset >= HEADER_SIZE) {
            val length = readU16Le(buffer, offset)
            val frameEnd = offset + HEADER_SIZE + length + TAG_SIZE
            if (buffer.size < frameEnd) break
            val aad = buffer.copyOfRange(offset, offset + HEADER_SIZE)
            val ciphertextAndTag = buffer.copyOfRange(offset + HEADER_SIZE, frameEnd)
            output.add(AirPlayCrypto.chachaOpen(readKey, AirPlayCrypto.nonce64(readCounter), ciphertextAndTag, aad))
            readCounter++
            offset = frameEnd
        }
        return Decrypted(concatBytes(*output.toTypedArray()), buffer.copyOfRange(offset, buffer.size))
    }

    /**
     * Fast path for RTSP event headers plus bplist bodies. Small HID events fit one control frame,
     * so encrypt both parts directly into the framed output without concatenating plaintext first.
     */
    fun encryptedSize(firstLength: Int, secondLength: Int): Int {
        require(firstLength >= 0 && secondLength >= 0)
        val total = firstLength + secondLength
        require(total <= MAX_PAYLOAD) { "control payload exceeds one-frame fast path" }
        return HEADER_SIZE + total + TAG_SIZE
    }

    fun encryptInto(
        first: ByteArray,
        firstOffset: Int,
        firstLength: Int,
        second: ByteArray,
        secondOffset: Int,
        secondLength: Int,
        target: ByteArray,
    ): Int {
        require(firstOffset >= 0 && firstLength >= 0 && firstOffset + firstLength <= first.size)
        require(secondOffset >= 0 && secondLength >= 0 && secondOffset + secondLength <= second.size)
        val total = firstLength + secondLength
        require(total <= MAX_PAYLOAD) { "control payload exceeds one-frame fast path" }
        val required = HEADER_SIZE + total + TAG_SIZE
        require(target.size >= required) { "control output target is too small" }

        writeHeader[0] = total.toByte()
        writeHeader[1] = (total ushr 8).toByte()
        target[0] = writeHeader[0]
        target[1] = writeHeader[1]
        val sealed = writeSealer.sealInto(
            nonce = AirPlayCrypto.nonce64(writeCounter, writeNonce),
            aad = writeHeader,
            first = first,
            firstOffset = firstOffset,
            firstLength = firstLength,
            second = second,
            secondOffset = secondOffset,
            secondLength = secondLength,
            target = target,
            targetOffset = HEADER_SIZE,
        )
        check(sealed == total + TAG_SIZE) { "unexpected control cipher output length" }
        writeCounter++
        return required
    }

    fun encrypt(first: ByteArray, second: ByteArray): ByteArray {
        val total = first.size + second.size
        if (total > MAX_PAYLOAD) return encrypt(first + second)
        return ByteArray(encryptedSize(first.size, second.size)).also { output ->
            encryptInto(
                first = first,
                firstOffset = 0,
                firstLength = first.size,
                second = second,
                secondOffset = 0,
                secondLength = second.size,
                target = output,
            )
        }
    }

    fun encrypt(plaintext: ByteArray): ByteArray {
        val output = ArrayList<ByteArray>()
        var offset = 0
        do {
            val chunk = plaintext.copyOfRange(offset, minOf(offset + MAX_PAYLOAD, plaintext.size))
            val header = byteArrayOf(chunk.size.toByte(), (chunk.size ushr 8).toByte())
            val ciphertextAndTag = AirPlayCrypto.chachaSeal(writeKey, AirPlayCrypto.nonce64(writeCounter), chunk, header)
            output.add(header)
            output.add(ciphertextAndTag)
            writeCounter++
            offset += MAX_PAYLOAD
        } while (offset < plaintext.size)
        return concatBytes(*output.toTypedArray())
    }

    private fun readU16Le(source: ByteArray, offset: Int): Int =
        (source[offset].toInt() and 0xff) or ((source[offset + 1].toInt() and 0xff) shl 8)

    private companion object {
        const val HEADER_SIZE = 2
        const val TAG_SIZE = 16
        const val MAX_PAYLOAD = 0x4000
    }
}
