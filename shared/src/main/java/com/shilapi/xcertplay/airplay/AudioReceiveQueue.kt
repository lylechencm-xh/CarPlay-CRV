package com.shilapi.xcertplay.airplay

/** Owns datagrams between the UDP reader and the single ordered authentication worker. */
class AudioReceiveQueue(
    private val capacity: Int = 64,
    private val maxAgeNs: Long = 80_000_000L,
) {
    class Packet {
        var bytes: ByteArray? = null
        var receivedNs = 0L
        internal var recyclable = false
    }
    data class Stats(val depth: Int, val highWater: Int, val overflow: Long, val expired: Long,
                     val cleanup: Long = 0L)
    private val lock = Object()
    private val packets = arrayOfNulls<ByteArray>(capacity)
    private val received = LongArray(capacity)
    private val owned = BooleanArray(capacity)
    private val reusable = java.util.ArrayDeque<ByteArray>()
    private var head = 0
    private var size = 0
    private var stopped = false
    private var highWater = 0
    private var overflow = 0L
    private var expired = 0L
    private var cleanup = 0L

    init { require(capacity > 0 && maxAgeNs > 0) }

    fun offer(bytes: ByteArray, receivedNs: Long = System.nanoTime()) = synchronized(lock) {
        offerOwned(bytes, receivedNs, false)
    }

    /** Copy once into bounded reusable storage, without borrowing the UDP reader's buffer. */
    fun offerCopy(source: ByteArray, length: Int, receivedNs: Long) = synchronized(lock) {
        require(length in 0..source.size)
        if (stopped) { cleanup++; return@synchronized }
        expire(receivedNs)
        val iterator = reusable.iterator()
        var target: ByteArray? = null
        while (iterator.hasNext()) {
            val candidate = iterator.next()
            if (candidate.size == length) { iterator.remove(); target = candidate; break }
        }
        val bytes = target ?: ByteArray(length)
        source.copyInto(bytes, 0, 0, length)
        offerOwned(bytes, receivedNs, true)
    }

    private fun offerOwned(bytes: ByteArray, receivedNs: Long, recyclable: Boolean) {
        // A datagram can finish receiving while close() retires the queue.
        if (stopped) { cleanup++; return }
        expire(receivedNs)
        if (size == capacity) { remove(); overflow++ }
        val tail = (head + size) % capacity
        packets[tail] = bytes
        owned[tail] = recyclable
        received[tail] = receivedNs
        size++
        highWater = maxOf(highWater, size)
        lock.notifyAll()
    }

    @Throws(InterruptedException::class)
    fun poll(target: Packet, nowNs: () -> Long = System::nanoTime): Boolean = synchronized(lock) {
        while (true) {
            expire(nowNs())
            if (stopped) { target.bytes = null; return@synchronized false }
            if (size > 0) {
                target.bytes = packets[head]
                target.receivedNs = received[head]
                target.recyclable = owned[head]
                remove(recycle = false)
                return@synchronized true
            }
            lock.wait()
        }
        @Suppress("UNREACHABLE_CODE")
        false
    }

    fun stats(): Stats = synchronized(lock) { Stats(size, highWater, overflow, expired, cleanup) }
    fun recycle(packet: Packet) = synchronized(lock) {
        if (packet.recyclable) packet.bytes?.let { retain(it) }
        packet.bytes = null
        packet.recyclable = false
    }
    fun close() = synchronized(lock) {
        stopped = true
        cleanup += size
        while (size > 0) remove()
        reusable.clear()
        lock.notifyAll()
    }
    private fun expire(nowNs: Long) {
        while (size > 0 && nowNs - received[head] > maxAgeNs) { remove(); expired++ }
    }
    private fun retain(bytes: ByteArray) {
        if (!stopped && bytes.size <= 4096 && reusable.size < capacity) reusable.addLast(bytes)
    }
    private fun remove(recycle: Boolean = true) {
        if (recycle && owned[head]) packets[head]?.let { retain(it) }
        packets[head] = null
        owned[head] = false
        head = (head + 1) % capacity
        size--
    }
}
