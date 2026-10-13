package com.shilapi.xcertplay

/**
 * Bounded allocation-free queue for decrypted RTP packets.
 *
 * Packet byte arrays are already owned by the renderer pipeline; this queue stores only references
 * and sample integers. When full it drops the oldest packet, matching the previous deque behavior.
 */
internal class CrvAudioPacketQueue(private val capacity: Int) {
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
    private var highWater = 0

    data class Stats(val depth: Int, val highWater: Int, val dropped: Long,
        val payloadBytes: Long, val oldestAgeNs: Long)
    fun stats(nowNs: Long = System.nanoTime()): Stats = synchronized(lock) {
        Stats(size, highWater, dropped, payloadBytes,
            if (size == 0) 0L else (nowNs - receivedNs[head]).coerceAtLeast(0L))
    }
    fun queuedPayloadBytes(): Long = synchronized(lock) { payloadBytes }

    init {
        require(capacity > 0)
    }

    fun offer(rtp: ByteArray, sample: Int, nowNs: Long = System.nanoTime()) {
        val start = CrvLegacyRtp.payloadStart(rtp)
        val end = CrvLegacyRtp.payloadEnd(rtp, start)
        val payloadSize = if (start >= 0 && end > start) end - start else 0
        synchronized(lock) {
            if (size == capacity) {
                dropped++
                payloadBytes -= payloadSizes[head]
                packets[head] = null
                head = (head + 1) % capacity
                size--
            }
            val tail = (head + size) % capacity
            packets[tail] = rtp
            samples[tail] = sample
            payloadSizes[tail] = payloadSize
            receivedNs[tail] = nowNs
            payloadBytes += payloadSize
            size++
            highWater = maxOf(highWater, size)
            lock.notify()
        }
    }

    @Throws(InterruptedException::class)
    fun poll(timeoutMillis: Long, target: MutablePacket): Boolean {
        require(timeoutMillis >= 0L)
        synchronized(lock) {
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

    private companion object {
        const val NANOS_PER_MILLISECOND = 1_000_000L
    }
}
