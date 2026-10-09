package com.shilapi.xcertplay.transport

/** Each startup stage is bounded; an active unlimited drive keeps bounded I/O polls. */
internal class Iap2ControlDeadline(
    private val timeoutMillis: Long,
    private val clockNanos: () -> Long = System::nanoTime,
    private val isSessionActive: () -> Boolean = { false },
) {
    private val unlimited = timeoutMillis == Long.MAX_VALUE
    private var stageStartedNanos = clockNanos()
    private var authenticated = false
    private var observedSessionActive = false
    private var startSessionSent = false

    fun authenticated() {
        authenticated = true
        if (unlimited) stageStartedNanos = clockNanos()
    }

    fun carPlayStartSent() {
        if (unlimited && !startSessionSent) {
            startSessionSent = true
            stageStartedNanos = clockNanos()
        }
    }

    fun remainingMillis(): Long {
        if (unlimited && authenticated) {
            if (!observedSessionActive && isSessionActive()) observedSessionActive = true
            if (observedSessionActive) return MAX_POLL_MILLIS
        }
        val budget = if (unlimited) HANDSHAKE_MILLIS else timeoutMillis
        val elapsedNanos = (clockNanos() - stageStartedNanos).coerceAtLeast(0)
        val remainingNanos = budget * 1_000_000 - elapsedNanos
        if (remainingNanos <= 0) return 0
        return ((remainingNanos + 999_999) / 1_000_000).coerceAtMost(MAX_POLL_MILLIS)
    }

    companion object {
        const val HANDSHAKE_MILLIS = 60_000L
        const val MAX_POLL_MILLIS = 30_000L
    }
}
