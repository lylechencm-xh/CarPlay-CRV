package com.shilapi.xcertplay

/** Poll output promptly while work is in the codec, without spinning forever on a stalled codec. */
internal class CrvCodecDrainSchedule {
    private var pending = 0
    private var submittedAtNs: Long? = null

    fun submitted(nowNs: Long) {
        if (pending < Int.MAX_VALUE) pending++
        submittedAtNs = nowNs
    }

    fun outputReady() { if (pending > 0) pending-- }
    fun reset() { pending = 0; submittedAtNs = null }

    fun queueWaitMillis(nowNs: Long): Long {
        val submitted = submittedAtNs ?: return IDLE_WAIT_MILLIS
        return if (pending > 0 && nowNs >= submitted && nowNs - submitted < FAST_WINDOW_NS) {
            5L
        } else IDLE_WAIT_MILLIS
    }

    companion object {
        private const val IDLE_WAIT_MILLIS = 20L
        private const val FAST_WINDOW_NS = 250_000_000L
    }
}
