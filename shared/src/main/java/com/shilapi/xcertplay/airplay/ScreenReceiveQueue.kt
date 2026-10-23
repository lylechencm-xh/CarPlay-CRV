package com.shilapi.xcertplay.airplay

/** Ordered, byte-bounded handoff. Config must not overtake frames in the same visibility epoch. */
internal class ScreenReceiveQueue(
    private val capacity: Int = 3,
    private val maxBytes: Int = 2 * 1024 * 1024,
) {
    class Packet {
        var header = ByteArray(128)
        var body = ByteArray(0)
        var length = 0
        var arrivalNs = 0L
        var nonce = 0L
        var visibilityEpoch = 0L
    }
    private val lock = Object()
    private val pending = java.util.ArrayDeque<Packet>()
    private val spare = java.util.ArrayDeque<Packet>()
    private var bytes = 0L
    private var closed = false
    private var finished = false

    init { require(capacity > 0 && maxBytes > 0) }

    @Throws(InterruptedException::class)
    fun offer(header: ByteArray, body: ByteArray, length: Int, arrivalNs: Long, nonce: Long,
              visibilityEpoch: Long = 0L): Boolean = synchronized(lock) {
        require(length in 0..body.size)
        // One oversized protocol-valid packet may progress on its own; it cannot grow the backlog.
        while (!closed && !finished && (pending.size >= capacity ||
                (pending.isNotEmpty() && bytes + length > maxBytes))) lock.wait()
        if (closed || finished) return@synchronized false
        val packet = if (spare.isEmpty()) Packet() else spare.removeFirst()
        header.copyInto(packet.header)
        if (packet.body.size < length) packet.body = ByteArray(length)
        body.copyInto(packet.body, 0, 0, length)
        packet.length = length
        packet.arrivalNs = arrivalNs
        packet.nonce = nonce
        packet.visibilityEpoch = visibilityEpoch
        pending.addLast(packet)
        bytes += length
        lock.notifyAll()
        true
    }

    @Throws(InterruptedException::class)
    fun take(): Packet? = synchronized(lock) {
        while (!closed && pending.isEmpty() && !finished) lock.wait()
        if (closed || pending.isEmpty()) return@synchronized null
        pending.removeFirst().also { bytes -= it.length; lock.notifyAll() }
    }

    fun recycle(packet: Packet) = synchronized(lock) {
        // Do not retain exceptional multi-megabyte keyframes for the rest of the session.
        if (packet.body.size > maxBytes) packet.body = ByteArray(0)
        if (!closed && spare.size < capacity + 1) spare.addLast(packet)
    }
    fun hasNewer(arrivalNs: Long): Boolean = synchronized(lock) {
        pending.peekLast()?.arrivalNs?.let { it > arrivalNs } == true
    }

    fun finish() = synchronized(lock) { finished = true; lock.notifyAll() }
    fun close() = synchronized(lock) {
        closed = true
        pending.clear(); spare.clear(); bytes = 0L
        lock.notifyAll()
    }
}
