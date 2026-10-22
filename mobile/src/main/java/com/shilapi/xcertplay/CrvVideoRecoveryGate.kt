package com.shilapi.xcertplay

/** A fresh reference chain needs time to reach first output before another soft reset. */
internal class CrvVideoRecoveryGate {
    private var lastRecoveryNs: Long? = null
    var suppressedTotal = 0L
        private set

    fun allow(nowNs: Long, waitingForKeyFrame: Boolean): Boolean {
        val previous = lastRecoveryNs
        // An established chain may have just lost frames to a queue overflow. Its
        // discontinuity must be handled immediately, even inside the previous cooldown.
        if (waitingForKeyFrame && previous != null && nowNs >= previous &&
            nowNs - previous < 4_000_000_000L) {
            suppressedTotal++
            return false
        }
        lastRecoveryNs = nowNs
        return true
    }
}
