package com.shilapi.xcertplay.airplay

/** Fixed 5ms buckets, saturated at 2 seconds. Single worker owns each histogram. */
class LatencyHistogram {
    private val buckets = LongArray(401)
    private var count = 0L
    fun record(nanos: Long) {
        buckets[(nanos.coerceAtLeast(0L) / 5_000_000L).coerceAtMost(400L).toInt()]++
        count++
    }
    fun percentileMs(percent: Int): Long {
        require(percent in 1..100)
        if (count == 0L) return 0L
        val rank = (count * percent + 99L) / 100L
        var sum = 0L
        for (i in buckets.indices) {
            sum += buckets[i]
            if (sum >= rank) return (i + 1L) * 5L
        }
        return 2_005L
    }
    fun reset() { buckets.fill(0L); count = 0L }
}
