package com.shilapi.xcertplay

/** Detects latency inside the codec even when the submit queue is empty. */
internal class CrvVideoOutputRecovery {
    private var latestSubmittedUs = 0L
    private var firstSubmittedNs: Long? = null
    private var latestOutputUs = 0L
    private var lastOutputNs: Long? = null
    private var episodeStartNs: Long? = null
    private var lastRecoveryNs: Long? = null
    private var submittedFrames = 0L
    private var outputFrames = 0L
    private var lastSubmittedNs = 0L
    var reason: String = ""; private set
    var triggerSnapshot: String = ""; private set

    fun submitted(presentationTimeUs: Long, submittedNs: Long = presentationTimeUs * 1000L) {
        if (firstSubmittedNs == null) firstSubmittedNs = submittedNs
        latestSubmittedUs = maxOf(latestSubmittedUs, presentationTimeUs)
        submittedFrames++
        lastSubmittedNs = submittedNs
    }

    fun reset() {
        latestSubmittedUs = 0L
        firstSubmittedNs = null
        latestOutputUs = 0L
        lastOutputNs = null
        episodeStartNs = null
        submittedFrames = 0L
        outputFrames = 0L
        lastSubmittedNs = 0L
    }

    fun observe(nowNs: Long, newestOutputUs: Long, outputs: Int = if (newestOutputUs > 0L) 1 else 0): Boolean {
        val nowUs = nowNs / 1000L
        if (newestOutputUs < 0L || newestOutputUs > nowUs || latestSubmittedUs > nowUs) {
            episodeStartNs = null
            return false
        }
        if (newestOutputUs > 0L) {
            latestOutputUs = maxOf(latestOutputUs, newestOutputUs)
            lastOutputNs = nowNs
            outputFrames += outputs.coerceAtLeast(0)
        }
        // An old last picture on a static screen is not decoder backlog. Require newer input.
        // Keep the last output timestamp across empty polls: a stalled codec returns no new output.
        val firstInput = firstSubmittedNs
        val startupStalled = latestOutputUs == 0L && firstInput != null &&
            nowNs - firstInput >= 4_000_000_000L
        val pendingOutput = latestOutputUs > 0L && latestSubmittedUs > latestOutputUs
        // PTS age includes steady pipeline latency. Empty polls between healthy frames
        // must not turn that latency into a stall and repeatedly flush the decoder.
        val lastOutput = lastOutputNs
        val outputStalled = pendingOutput && lastOutput != null &&
            nowNs - lastOutput >= 1_500_000_000L &&
            latestSubmittedUs - latestOutputUs >= 250_000L
        // A lone pending frame gets a longer timeout; a fully rendered static screen
        // still has no pending input and never triggers recovery.
        val tailStalled = pendingOutput && lastOutput != null &&
            nowNs - lastOutput >= 4_000_000_000L &&
            nowNs - lastSubmittedNs >= 4_000_000_000L
        // Even with fresh output, recover if the queued media span stays severely behind.
        val outputLagged = pendingOutput && latestSubmittedUs - latestOutputUs >= 1_500_000L
        if (!startupStalled && !outputStalled && !tailStalled && !outputLagged) {
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
        triggerSnapshot = "inputPtsUs=$latestSubmittedUs outputPtsUs=$latestOutputUs " +
            "pendingSpanUs=${if (latestOutputUs > 0L) (latestSubmittedUs - latestOutputUs).coerceAtLeast(0L).toString() else "unknown"} " +
            "lastOutputAgoMs=${lastOutput?.let { (nowNs - it) / 1_000_000L } ?: -1L} " +
            "submittedFrames=$submittedFrames outputFrames=$outputFrames " +
            "lastSubmittedAgoMs=${(nowNs - lastSubmittedNs).coerceAtLeast(0L) / 1_000_000L}"
        reason = if (startupStalled) "startup output timeout"
            else if (outputStalled) "output stalled"
            else if (tailStalled) "tail stalled" else "output lag persisted"
        reset()
        return true
    }
}
