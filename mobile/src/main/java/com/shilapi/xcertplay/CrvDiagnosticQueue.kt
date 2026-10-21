package com.shilapi.xcertplay

/** Bounded FIFO; producers never wait for storage. Terminal events displace routine telemetry. */
internal class CrvDiagnosticQueue(private val capacity: Int = 256) {
    data class Entry(val message: String, val wallMs: Long, val elapsedMs: Long, val important: Boolean)
    private val lock = Object()
    private val entries = java.util.ArrayDeque<Entry>()
    private var closed = false
    private var dropped = 0L
    init { require(capacity > 0) }
    fun offer(entry: Entry) = synchronized(lock) {
        if (closed) return@synchronized
        if (entries.size >= capacity) {
            if (!entry.important) { dropped++; return@synchronized }
            val iterator = entries.iterator()
            var removed = false
            while (iterator.hasNext()) {
                if (!iterator.next().important) { iterator.remove(); removed = true; break }
            }
            if (!removed) entries.removeFirst()
            dropped++
        }
        entries.addLast(entry)
        lock.notifyAll()
    }
    fun take(): Entry? = synchronized(lock) {
        while (entries.isEmpty() && !closed) lock.wait()
        if (entries.isEmpty()) null else entries.removeFirst()
    }
    fun dropped(): Long = synchronized(lock) { dropped }
    fun close() = synchronized(lock) { closed = true; lock.notifyAll() }
}
