package com.shilapi.xcertplay.transport

/**
 * Frames NTB16 blocks from the USB byte stream.
 *
 * Some peers transmit a 512-byte NTB followed by a zero-byte short-packet marker;
 * others end the transfer with a USB zero-length packet (which contributes no bytes).
 * Linux CDC-NCM can also include a short-packet byte in wBlockLength. Never wait for
 * a mandatory extra byte when wBlockLength is already complete.
 */
internal class Ntb16WireDecoder {
    private var buffered = ByteArray(0)
    private var bufferedSize = 0
    private var optionalPadPending = false

    fun append(source: ByteArray, length: Int = source.size): List<ByteArray> {
        require(length in 0..source.size) { "Invalid NCM USB read length" }
        if (length > 0) {
            val required = bufferedSize + length
            if (required > buffered.size) {
                val resized = ByteArray(maxOf(required, maxOf(16 * 1024, buffered.size * 2)))
                buffered.copyInto(resized, 0, 0, bufferedSize)
                buffered = resized
            }
            source.copyInto(buffered, bufferedSize, 0, length)
            bufferedSize += length
        }

        val blocks = ArrayList<ByteArray>()
        while (true) {
            if (optionalPadPending) {
                if (bufferedSize == 0) break
                // The next NTH16 starts with ASCII 'N', never a zero byte.
                if (buffered[0] == 0.toByte()) consume(1)
                optionalPadPending = false
            }
            if (bufferedSize < 12) break
            val signature =
                (buffered[0].toInt() and 0xff) or
                    ((buffered[1].toInt() and 0xff) shl 8) or
                    ((buffered[2].toInt() and 0xff) shl 16) or
                    ((buffered[3].toInt() and 0xff) shl 24)
            require(signature == Ntb16Codec.NTH16_SIG) {
                "NCM wire data does not begin with an NTH16 header"
            }
            val blockLength =
                (buffered[8].toInt() and 0xff) or ((buffered[9].toInt() and 0xff) shl 8)
            require(blockLength >= 28) { "Invalid NTB16 block length " + blockLength }
            if (bufferedSize < blockLength) break
            blocks.add(buffered.copyOfRange(0, blockLength))
            consume(blockLength)
            optionalPadPending = blockLength % 512 == 0
        }
        return blocks
    }

    private fun consume(count: Int) {
        val remaining = bufferedSize - count
        buffered.copyInto(buffered, 0, count, bufferedSize)
        bufferedSize = remaining
    }
}

/** API17 bulkTransfer() silently truncates a requested transfer above 16 KiB. */
internal object NcmLegacyUsbBulk {
    const val MAX_TRANSACTION_BYTES = 16 * 1024

    fun chunkSize(remaining: Int): Int {
        require(remaining > 0) { "Bulk transfer needs bytes" }
        return minOf(remaining, MAX_TRANSACTION_BYTES)
    }
}

/**
 * A negative bulkTransfer result is also how Android reports an ordinary timeout.
 * Report a USB detach only after two independent missing-device observations;
 * never infer a detach from NAKs or idle read timeouts alone.
 */
internal class NcmUsbPresenceGuard(private val isAttached: (() -> Boolean)?) {
    private var missingChecks = 0

    @Synchronized
    fun disconnectedAfterFailures(consecutiveFailures: Int): Boolean {
        val observer = isAttached ?: return false
        if (consecutiveFailures < 64 || consecutiveFailures % 64 != 0) return false
        val stillAttached = runCatching { observer.invoke() }.getOrNull()
        missingChecks = if (stillAttached == false) missingChecks + 1 else 0
        return missingChecks >= 2
    }

    @Synchronized
    fun reset() {
        missingChecks = 0
    }
}
