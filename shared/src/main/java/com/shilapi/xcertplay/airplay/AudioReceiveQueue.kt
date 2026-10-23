package com.shilapi.xcertplay.airplay

/** Owns datagrams between the UDP reader and the single ordered authentication worker. */
class AudioReceiveQueue(
    private val capacity: Int = 64,
    private val maxAgeNs: Long = 80_000_000L,
) {
    class Packet {
        var bytes: ByteArray? = null
        var receivedNs = 0L
    }
    data class Stats(val depth: Int, val highWater: Int, val overflow: Long, val expired: Long,
                     val cleanup: Long = 0L)
    private val lock = Object()
    private val packets = arrayOfNulls<ByteArray>(capacity)
    private val received = LongArray(capacity)
    private var head = 0
    private var size = 0
    private var stopped = false
    private var highWater = 0
    private var overflow = 0L
    private var expired = 0L
    private var cleanup = 0L

    init { require(capacity > 0 && maxAgeNs > 0) }

    fun offer(bytes: ByteArray, receivedNs: Long = System.nanoTime()) = synchronized(lock) {
        if (stopped) return@synchronized
        expire(receivedNs)
        if (size == capacity) { remove(); overflow++ }
        val tail = (head + size) % capacity
        packets[tail] = bytes
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
                remove()
                return@synchronized true
            }
            lock.wait()
        }
        @Suppress("UNREACHABLE_CODE")
        false
    }

    fun stats(): Stats = synchronized(lock) { Stats(size, highWater, overflow, expired, cleanup) }
    fun close() = synchronized(lock) {
        stopped = true
        cleanup += size
        while (size > 0) remove()
        lock.notifyAll()
    }
    private fun expire(nowNs: Long) {
        while (size > 0 && nowNs - received[head] > maxAgeNs) { remove(); expired++ }
    }
    private fun remove() {
        packets[head] = null
        head = (head + 1) % capacity
        size--
    }
}
