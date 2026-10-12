package com.shilapi.xcertplay.transport

/**
 * Frames NTB16 blocks from the USB byte stream.
 *
 * Some peers transmit a packet-aligned NTB followed by a zero-byte short-packet marker;
 * others end the transfer with a USB zero-length packet (which contributes no bytes).
 * Linux CDC-NCM can also include a short-packet byte in wBlockLength. Never wait for
 * a mandatory extra byte when wBlockLength is already complete.
 */
internal class Ntb16WireDecoder(
    private val packetSize: Int = 512,
    private val clockNanos: () -> Long = System::nanoTime,
    private val onResync: (Int, Long) -> Unit = { _, _ -> },
) {
    init { require(packetSize > 0) }
    private var buffered = ByteArray(0)
    private var bufferedSize = 0
    private var optionalPadPending = false
    private var resyncCount = 0
    private var resyncWindowStart: Long? = null
    @Volatile var resynchronizations = 0L
        private set
    @Volatile var discardedBytes = 0L
        private set

    fun append(source: ByteArray, length: Int = source.size): List<ByteArray> =
        appendInternal(source, length, framesOnly = false)

    /**
     * Reassembles NTB16 blocks but returns their Ethernet datagrams directly.
     * This avoids copying each complete NTB only to copy its contained frame again.
     */
    fun appendFrames(source: ByteArray, length: Int = source.size): List<ByteArray> =
        appendInternal(source, length, framesOnly = true)

    private fun appendInternal(
        source: ByteArray,
        length: Int,
        framesOnly: Boolean,
    ): List<ByteArray> {
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

        val output = ArrayList<ByteArray>()
        while (true) {
            if (optionalPadPending) {
                if (bufferedSize == 0) break
                // The next NTH16 starts with ASCII 'N', never a zero byte.
                if (buffered[0] == 0.toByte()) consume(1)
                optionalPadPending = false
            }
            if (bufferedSize < 12) break
            if (!plausibleHeader(0)) {
                resync()
                continue
            }
            val blockLength = readU16(8)
            if (bufferedSize < blockLength) break

            if (framesOnly) {
                output.addAll(Ntb16Codec.parse(buffered, 0, blockLength))
            } else {
                output.add(buffered.copyOfRange(0, blockLength))
            }
            consume(blockLength)
            optionalPadPending = blockLength % packetSize == 0
        }
        return output
    }

    private fun plausibleHeader(offset: Int): Boolean {
        if (offset + 12 > bufferedSize) return false
        val signature =
            (buffered[offset].toInt() and 0xff) or
                ((buffered[offset + 1].toInt() and 0xff) shl 8) or
                ((buffered[offset + 2].toInt() and 0xff) shl 16) or
                ((buffered[offset + 3].toInt() and 0xff) shl 24)
        if (signature != Ntb16Codec.NTH16_SIG || readU16(offset + 4) != 12) return false
        val blockLength = readU16(offset + 8)
        val ndpIndex = readU16(offset + 10)
        return blockLength >= 28 && ndpIndex >= 12 && ndpIndex <= blockLength - 16
    }

    private fun readU16(offset: Int): Int =
        (buffered[offset].toInt() and 0xff) or
            ((buffered[offset + 1].toInt() and 0xff) shl 8)

    private fun resync() {
        val now = clockNanos()
        val windowStart = resyncWindowStart
        if (windowStart == null || now - windowStart >= 10_000_000_000L) {
            resyncWindowStart = now
            resyncCount = 0
        }
        require(++resyncCount <= 16) { "NCM wire data repeatedly lost NTB16 alignment" }
        val next = (1..bufferedSize - 12).firstOrNull(::plausibleHeader)
        // Preserve a possible split header at the end of this USB read.
        val skipped = next ?: maxOf(1, bufferedSize - 11)
        consume(skipped)
        resynchronizations++
        discardedBytes += skipped
        runCatching { onResync(skipped, resynchronizations) }
        optionalPadPending = false
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
