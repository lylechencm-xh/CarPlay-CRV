package com.shilapi.xcertplay

/** Bounded FIFO; producers never wait for storage. Terminal events displace routine telemetry. */
internal class CrvDiagnosticQueue(private val capacity: Int = 256) {
    data class Entry(val message: String, val wallMs: Long, val elapsedMs: Long, val important: Boolean,
                     val critical: Boolean = false) {
        val priority: Int get() = if (critical) 2 else if (important) 1 else 0
    }
    private val lock = Object()
    private val entries = java.util.ArrayDeque<Entry>()
    private var closed = false
    private var dropped = 0L
    private val droppedByPriority = LongArray(3)
    init { require(capacity > 0) }
    fun offer(entry: Entry) = synchronized(lock) {
        if (closed) return@synchronized
        if (entries.size >= capacity) {
            val lowestPriority = entries.minOf { it.priority }
            // Never let lower-priority traffic evict terminal/error/performance evidence.
            if (lowestPriority > entry.priority || entry.priority == 0) {
                dropped++; droppedByPriority[entry.priority]++; return@synchronized
            }
            val iterator = entries.iterator()
            while (iterator.hasNext()) {
                val candidate = iterator.next()
                if (candidate.priority == lowestPriority) { iterator.remove(); break }
            }
            dropped++
            droppedByPriority[lowestPriority]++
        }
        entries.addLast(entry)
        lock.notifyAll()
    }
    fun take(): Entry? = synchronized(lock) {
        while (entries.isEmpty() && !closed) lock.wait()
        if (entries.isEmpty()) null else entries.removeFirst()
    }
    fun dropped(): Long = synchronized(lock) { dropped }
    fun dropSummary(): String = synchronized(lock) {
        "Diagnostics queue droppedTotal=$dropped droppedRoutine=${droppedByPriority[0]} " +
            "droppedImportant=${droppedByPriority[1]} droppedCritical=${droppedByPriority[2]}"
    }
    fun close() = synchronized(lock) { closed = true; lock.notifyAll() }
}
