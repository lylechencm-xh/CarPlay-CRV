package com.shilapi.xcertplay

/** Detects latency inside the codec even when the submit queue is empty. */
internal class CrvVideoOutputRecovery {
    private var latestSubmittedUs = 0L
    private var firstSubmittedUs = 0L
    private var latestOutputUs = 0L
    private var episodeStartNs: Long? = null
    private var lastRecoveryNs: Long? = null
    var reason: String = ""; private set

    fun submitted(presentationTimeUs: Long) {
        if (firstSubmittedUs == 0L) firstSubmittedUs = presentationTimeUs
        latestSubmittedUs = maxOf(latestSubmittedUs, presentationTimeUs)
    }

    fun reset() {
        latestSubmittedUs = 0L
        firstSubmittedUs = 0L
        latestOutputUs = 0L
        episodeStartNs = null
    }

    fun observe(nowNs: Long, newestOutputUs: Long): Boolean {
        val nowUs = nowNs / 1000L
        if (newestOutputUs < 0L || newestOutputUs > nowUs || latestSubmittedUs > nowUs) {
            episodeStartNs = null
            return false
        }
        if (newestOutputUs > 0L) latestOutputUs = maxOf(latestOutputUs, newestOutputUs)
        // An old last picture on a static screen is not decoder backlog. Require newer input.
        // Keep the last output timestamp across empty polls: a stalled codec returns no new output.
        val startupStalled = latestOutputUs == 0L && firstSubmittedUs > 0L &&
            nowUs - firstSubmittedUs >= 4_000_000L
        val pendingOutput = latestOutputUs > 0L && latestSubmittedUs > latestOutputUs
        val outputLagged = pendingOutput && nowUs - latestOutputUs >= 500_000L &&
            latestSubmittedUs - latestOutputUs >= 250_000L
        val singleFrameStalled = pendingOutput && nowUs - latestSubmittedUs >= 1_500_000L
        if (!startupStalled && !outputLagged && !singleFrameStalled) {
            episodeStartNs = null
            return false
        }
        val previous = lastRecoveryNs
        if (previous != null && nowNs - previous < 3_000_000_000L) {
            episodeStartNs = null
            return false
        }
        val start = episodeStartNs
        if (start == null || nowNs < start) {
            episodeStartNs = nowNs
            return false
        }
        if (nowNs - start < 750_000_000L) return false
        lastRecoveryNs = nowNs
        reason = if (startupStalled) "startup output timeout"
            else if (newestOutputUs == 0L) "output stalled" else "output lag persisted"
        reset()
        return true
    }
}
