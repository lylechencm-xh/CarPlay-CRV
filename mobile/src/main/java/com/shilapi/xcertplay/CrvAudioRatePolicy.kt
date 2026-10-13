package com.shilapi.xcertplay

/** Conservative clock correction for sustained LPCM music backlog; never trims audio packets. */
internal class CrvAudioRatePolicy(private val sampleRate: Int) {
    private var highSinceNs: Long? = null
    private var correcting = false
    private var lastStepNs: Long? = null
    private var rate = sampleRate
    init { require(sampleRate > 0) }

    fun desiredRate(nowNs: Long, queueMs: Long, playing: Boolean): Int {
        if (!playing || queueMs <= 100L) {
            correcting = false
            highSinceNs = null
        } else if (queueMs >= 300L) {
            val start = highSinceNs
            if (start == null || nowNs < start) highSinceNs = nowNs
            else if (nowNs - start >= 2_000_000_000L) correcting = true
        } else if (!correcting) highSinceNs = null

        if (!playing) {
            rate = sampleRate
            lastStepNs = null
            return rate
        }
        val target = if (correcting) sampleRate + sampleRate / 200 else sampleRate
        val last = lastStepNs
        if (last == null || nowNs < last || nowNs - last >= 500_000_000L) {
            val step = maxOf(1, sampleRate / 1000)
            rate = if (rate < target) minOf(target, rate + step) else maxOf(target, rate - step)
            lastStepNs = nowNs
        }
        return rate
    }
}
