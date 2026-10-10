package com.shilapi.xcertplay.network

import com.shilapi.xcertplay.transport.NcmWriteResult
import java.io.IOException
import java.util.concurrent.locks.LockSupport

/** Keeps one outbound frame until USB accepts it, or reports a bounded write failure. */
internal class NcmPendingFrameSender(
    private val transmit: (ByteArray) -> NcmWriteResult,
    private val isRunning: () -> Boolean,
    private val clockNanos: () -> Long = System::nanoTime,
    private val pause: (Long) -> Unit = { LockSupport.parkNanos(it) },
) {
    fun send(frame: ByteArray): Boolean {
        val startedNanos = clockNanos()
        var retryPauseNanos = INITIAL_RETRY_PAUSE_NANOS
        while (isRunning()) {
            when (transmit(frame)) {
                NcmWriteResult.SENT -> return true
                NcmWriteResult.NOT_READY -> {
                    if (clockNanos() - startedNanos >= MAX_PENDING_NANOS) {
                        throw IOException("NCM bulk OUT stayed unavailable for 90 seconds")
                    }
                    if (Thread.currentThread().isInterrupted) {
                        throw IOException("NCM outbound writer interrupted")
                    }
                    pause(retryPauseNanos)
                    retryPauseNanos = minOf(retryPauseNanos * 2, MAX_RETRY_PAUSE_NANOS)
                }
            }
        }
        return false
    }

    private companion object {
        const val INITIAL_RETRY_PAUSE_NANOS = 1_000_000L
        const val MAX_RETRY_PAUSE_NANOS = 20_000_000L
        const val MAX_PENDING_NANOS = 90_000_000_000L
    }
}
