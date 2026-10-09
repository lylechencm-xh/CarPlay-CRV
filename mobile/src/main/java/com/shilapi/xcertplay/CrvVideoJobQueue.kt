package com.shilapi.xcertplay

import com.shilapi.xcertplay.airplay.VideoCodec

/**
 * Fixed-capacity API17 video work queue.
 *
 * Stores frame references and metadata in arrays so 30 fps playback does not allocate a deque node
 * plus a Frame job for every decoded picture. The decoder thread owns one reusable [Holder].
 */
internal class CrvVideoJobQueue(
    private val capacity: Int,
    private val maxFrameBytes: Int,
) {
    class Holder {
        var type: Int = TYPE_NONE
        var codec: VideoCodec? = null
        var data: ByteArray? = null
        var receivedNs: Long = 0L
        var reason: String? = null

        fun clear() {
            type = TYPE_NONE
            codec = null
            data = null
            receivedNs = 0L
            reason = null
        }
    }

    data class Backlog(
        val pendingFrames: Int,
        val newestPendingReceivedNs: Long?,
    )

    enum class FrameOffer {
        ADDED,
        FRAME_LIMIT,
        BYTE_LIMIT,
    }

    private val lock = Object()
    private val types = IntArray(capacity)
    private val codecs = arrayOfNulls<VideoCodec>(capacity)
    private val data = arrayOfNulls<ByteArray>(capacity)
    private val received = LongArray(capacity)
    private val reasons = arrayOfNulls<String>(capacity)
    private var head = 0
    private var size = 0
    private var frameBytes = 0L

    init {
        require(capacity >= 3)
        require(maxFrameBytes > 0)
    }

    fun offerConfig(codec: VideoCodec, config: ByteArray) = synchronized(lock) {
        if (size == capacity) clearLocked()
        addLocked(TYPE_CONFIG, codec, config, 0L, null)
        lock.notify()
    }

    fun offerFrame(frame: ByteArray, receivedNs: Long): FrameOffer = synchronized(lock) {
        if (frame.size > maxFrameBytes) return@synchronized FrameOffer.BYTE_LIMIT
        if (frameBytes + frame.size > maxFrameBytes) return@synchronized FrameOffer.BYTE_LIMIT
        if (size == capacity) return@synchronized FrameOffer.FRAME_LIMIT
        addLocked(TYPE_FRAME, null, frame, receivedNs, null)
        lock.notify()
        FrameOffer.ADDED
    }

    /**
     * Drops stale queued work but preserves the newest pending config, then queues recovery and the
     * newest frame when it fits. This matches the old recovery ordering without allocating jobs.
     */
    fun recover(frame: ByteArray?, receivedNs: Long, reason: String) = synchronized(lock) {
        var pendingCodec: VideoCodec? = null
        var pendingConfig: ByteArray? = null
        for (offset in 0 until size) {
            val index = index(offset)
            if (types[index] == TYPE_CONFIG) {
                pendingCodec = codecs[index]
                pendingConfig = data[index]
            }
        }
        clearLocked()
        if (pendingCodec != null && pendingConfig != null) {
            addLocked(TYPE_CONFIG, pendingCodec, pendingConfig, 0L, null)
        }
        addLocked(TYPE_RECOVER, null, null, 0L, reason)
        if (
            frame != null &&
            frame.size <= maxFrameBytes &&
            size < capacity
        ) {
            addLocked(TYPE_FRAME, null, frame, receivedNs, null)
        }
        lock.notify()
    }

    @Throws(InterruptedException::class)
    fun poll(timeoutMillis: Long, target: Holder): Boolean {
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
                target.clear()
                return false
            }

            target.type = types[head]
            target.codec = codecs[head]
            target.data = data[head]
            target.receivedNs = received[head]
            target.reason = reasons[head]
            if (types[head] == TYPE_FRAME) {
                frameBytes -= data[head]?.size?.toLong() ?: 0L
            }
            clearSlot(head)
            head = (head + 1) % capacity
            size--
            return true
        }
    }

    fun backlogAfterCurrent(): Backlog = synchronized(lock) {
        var pending = 0
        var newest: Long? = null
        for (offset in 0 until size) {
            val index = index(offset)
            if (types[index] != TYPE_FRAME) break
            pending++
            val time = received[index]
            newest = newest?.let { maxOf(it, time) } ?: time
        }
        Backlog(pending, newest)
    }

    fun discardLeadingFrames() = synchronized(lock) {
        while (size > 0 && types[head] == TYPE_FRAME) {
            frameBytes -= data[head]?.size?.toLong() ?: 0L
            clearSlot(head)
            head = (head + 1) % capacity
            size--
        }
    }

    internal fun pendingFrameBytes(): Long = synchronized(lock) { frameBytes }
    internal fun size(): Int = synchronized(lock) { size }

    private fun addLocked(
        type: Int,
        codec: VideoCodec?,
        bytes: ByteArray?,
        timeNs: Long,
        reason: String?,
    ) {
        val tail = index(size)
        types[tail] = type
        codecs[tail] = codec
        data[tail] = bytes
        received[tail] = timeNs
        reasons[tail] = reason
        if (type == TYPE_FRAME) frameBytes += bytes?.size?.toLong() ?: 0L
        size++
    }

    private fun clearLocked() {
        for (offset in 0 until size) clearSlot(index(offset))
        head = 0
        size = 0
        frameBytes = 0L
    }

    private fun clearSlot(index: Int) {
        types[index] = TYPE_NONE
        codecs[index] = null
        data[index] = null
        received[index] = 0L
        reasons[index] = null
    }

    private fun index(offset: Int): Int = (head + offset) % capacity

    companion object {
        const val TYPE_NONE = 0
        const val TYPE_CONFIG = 1
        const val TYPE_FRAME = 2
        const val TYPE_RECOVER = 3
        private const val NANOS_PER_MILLISECOND = 1_000_000L
    }
}
