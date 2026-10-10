package com.shilapi.xcertplay

import java.io.Closeable
import java.util.ArrayDeque

internal data class CrvTouchFrame<T>(
    val target: T, val x0: Double, val y0: Double, val down0: Boolean,
    val x1: Double, val y1: Double, val down1: Boolean, val move: Boolean,
    val queuedNs: Long = System.nanoTime(),
)

/** Bounded input history: replace only consecutive MOVE frames, never a normal gesture edge. */
internal class CrvTouchQueue<T>(private val capacity: Int = 32) {
    private val frames = ArrayDeque<CrvTouchFrame<T>>()
    var coalesced = 0L; private set
    var resets = 0L; private set
    val size: Int get() = frames.size
    init { require(capacity >= 2) }

    fun offer(frame: CrvTouchFrame<T>) {
        val last = frames.peekLast()
        if (frame.move && last?.move == true && last.target === frame.target &&
            last.down0 == frame.down0 && last.down1 == frame.down1) {
            frames.removeLast()
            coalesced++
        }
        if (frames.size >= capacity) {
            val iterator = frames.iterator()
            var removed = false
            while (iterator.hasNext()) {
                if (iterator.next().move) { iterator.remove(); removed = true; coalesced++; break }
            }
            if (!removed) {
                // A stalled channel filled entirely with edges. Resynchronize released fingers
                // before the current state rather than queue unlimited old gestures.
                frames.clear()
                frames.add(frame.copy(down0 = false, down1 = false, move = false))
                resets++
            }
        }
        frames.add(frame)
    }
    fun poll(): CrvTouchFrame<T>? = frames.pollFirst()
    fun clear() = frames.clear()
}

/** Socket writes and event-channel locks must never run on Android's UI thread. */
internal class CrvTouchDispatcher<T>(
    private val send: (CrvTouchFrame<T>) -> Boolean,
    private val report: (String) -> Unit,
) : Closeable {
    private val lock = java.lang.Object()
    private val queue = CrvTouchQueue<T>()
    @Volatile private var closed = false
    private val worker = Thread(::run, "crv-touch-tx").apply { isDaemon = true; start() }

    fun offer(frame: CrvTouchFrame<T>): Boolean = synchronized(lock) {
        if (closed) return false
        queue.offer(frame)
        lock.notifyAll()
        true // Accepted for asynchronous delivery; not a peer acknowledgement.
    }

    override fun close() {
        synchronized(lock) { closed = true; queue.clear(); lock.notifyAll() }
        worker.interrupt() // The controller closes session sockets to unblock pending writes.
    }

    private fun run() {
        var sent = 0L
        var failures = 0L
        var sendMaxNs = 0L
        var queueMaxNs = 0L
        var reportAt = System.nanoTime()
        try {
            while (!closed) {
                val frame = synchronized(lock) {
                    if (queue.size == 0 && !closed) lock.wait(1_000L)
                    if (closed) null else queue.poll()
                }
                if (frame != null && !closed) {
                    val start = System.nanoTime()
                    queueMaxNs = maxOf(queueMaxNs, start - frame.queuedNs)
                    if (runCatching { send(frame) }.getOrDefault(false)) sent++ else failures++
                    sendMaxNs = maxOf(sendMaxNs, System.nanoTime() - start)
                }
                val now = System.nanoTime()
                if (now - reportAt >= 5_000_000_000L) {
                    val snapshot = synchronized(lock) { "depth=${queue.size} coalesced=${queue.coalesced} resets=${queue.resets}" }
                    runCatching { report("Touch delivery $snapshot sent=$sent failures=$failures " +
                        "sendMaxUs=${sendMaxNs / 1000} queueMaxUs=${queueMaxNs / 1000}") }
                    sendMaxNs = 0L; queueMaxNs = 0L; reportAt = now
                }
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }
}
