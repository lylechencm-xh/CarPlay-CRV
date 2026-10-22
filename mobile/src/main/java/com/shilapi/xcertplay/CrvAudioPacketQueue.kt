package com.shilapi.xcertplay

/**
 * Bounded allocation-free queue for decrypted RTP packets.
 *
 * Packet byte arrays are already owned by the renderer pipeline; this queue stores only references
 * and sample integers. When full it drops the oldest packet, matching the previous deque behavior.
 */
internal class CrvAudioPacketQueue(
    private val capacity: Int,
    private val maxAgeNs: Long = Long.MAX_VALUE,
    private val maxPayloadBytes: Long = Long.MAX_VALUE,
) {
    class MutablePacket {
        var rtp: ByteArray? = null
        var sample: Int = 0
    }

    private val lock = Object()
    private val packets = arrayOfNulls<ByteArray>(capacity)
    private val samples = IntArray(capacity)
    private val payloadSizes = IntArray(capacity)
    private val receivedNs = LongArray(capacity)
    private var payloadBytes = 0L
    private var head = 0
    private var size = 0
    private var dropped = 0L
    private var expired = 0L
    private var highWater = 0

    data class Stats(val depth: Int, val highWater: Int, val dropped: Long,
        val payloadBytes: Long, val oldestAgeNs: Long, val expired: Long = 0L)
    fun stats(nowNs: Long = System.nanoTime()): Stats = synchronized(lock) {
        Stats(size, highWater, dropped, payloadBytes,
            if (size == 0) 0L else (nowNs - receivedNs[head]).coerceAtLeast(0L), expired)
    }
    fun queuedPayloadBytes(): Long = synchronized(lock) { payloadBytes }

    init {
        require(capacity > 0)
    }

    fun offer(rtp: ByteArray, sample: Int, nowNs: Long = System.nanoTime(), receivedAtNs: Long = nowNs) {
        val start = CrvLegacyRtp.payloadStart(rtp)
        val end = CrvLegacyRtp.payloadEnd(rtp, start)
        val payloadSize = if (start >= 0 && end > start) end - start else 0
        synchronized(lock) {
            discardExpiredLocked(nowNs)
            // Keep the original UDP clock across authentication and handoff. A delayed
            // callback must not restart this packet's entire renderer queue allowance.
            if (nowNs - receivedAtNs > maxAgeNs) {
                dropped++
                expired++
                return
            }
            if (payloadSize > maxPayloadBytes) {
                dropped++
                return
            }
            while (size > 0 && (size == capacity || payloadBytes + payloadSize > maxPayloadBytes)) {
                dropHeadLocked()
            }
            val tail = (head + size) % capacity
            packets[tail] = rtp
            samples[tail] = sample
            payloadSizes[tail] = payloadSize
            receivedNs[tail] = receivedAtNs
            payloadBytes += payloadSize
            size++
            highWater = maxOf(highWater, size)
            lock.notify()
        }
    }

    @Throws(InterruptedException::class)
    fun poll(timeoutMillis: Long, target: MutablePacket, nowNs: () -> Long = System::nanoTime): Boolean {
        require(timeoutMillis >= 0L)
        synchronized(lock) {
            discardExpiredLocked(nowNs())
            if (size == 0 && timeoutMillis > 0L) {
                val deadline = System.nanoTime() + timeoutMillis * NANOS_PER_MILLISECOND
                while (size == 0) {
                    val remaining = deadline - System.nanoTime()
                    if (remaining <= 0L) break
                    lock.wait(
                        remaining / NANOS_PER_MILLISECOND,
                        (remaining % NANOS_PER_MILLISECOND).toInt(),
                    )
                }
            }
            discardExpiredLocked(nowNs())
            if (size == 0) {
                target.rtp = null
                return false
            }

            target.rtp = packets[head]
            target.sample = samples[head]
            payloadBytes -= payloadSizes[head]
            payloadSizes[head] = 0
            packets[head] = null
            head = (head + 1) % capacity
            size--
            return true
        }
    }

    fun isEmpty(): Boolean = synchronized(lock) { size == 0 }

    internal fun size(): Int = synchronized(lock) { size }

    private fun discardExpiredLocked(nowNs: Long) {
        while (size > 0 && nowNs - receivedNs[head] > maxAgeNs) {
            dropHeadLocked()
            expired++
        }
    }

    private fun dropHeadLocked() {
        dropped++
        payloadBytes -= payloadSizes[head]
        payloadSizes[head] = 0
        packets[head] = null
        head = (head + 1) % capacity
        size--
    }

    private companion object {
        const val NANOS_PER_MILLISECOND = 1_000_000L
    }
}
