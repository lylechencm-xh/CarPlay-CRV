package com.shilapi.xcertplay.network

import java.io.IOException
import java.util.concurrent.locks.LockSupport

/** Retry only explicitly transient packet-write errors; preserve order and bound congestion. */
internal class TunPendingPacketWriter(
    private val isRunning: () -> Boolean,
    private val isWouldBlock: (IOException) -> Boolean,
    private val clockNanos: () -> Long = System::nanoTime,
    private val pause: (Long) -> Unit = { LockSupport.parkNanos(it) },
) {
    fun write(writePacket: () -> Unit): Boolean {
        val started = clockNanos()
        var delay = 1_000_000L
        while (isRunning()) {
            try {
                writePacket()
                return true
            } catch (error: IOException) {
                if (!isWouldBlock(error)) throw error
                if (clockNanos() - started >= 5_000_000_000L) {
                    throw IOException("TUN receive stayed congested for five seconds", error)
                }
                if (Thread.currentThread().isInterrupted) throw IOException("TUN packet writer interrupted", error)
                pause(delay)
                delay = minOf(delay * 2, 20_000_000L)
            }
        }
        return false
    }
}
