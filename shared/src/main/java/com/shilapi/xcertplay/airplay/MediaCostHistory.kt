package com.shilapi.xcertplay.airplay

/** Per-worker ring of five-second summaries; the existing diagnostic sink queues disk I/O. */
internal class MediaCostHistory(
    private val worker: String,
    private val report: (String) -> Unit,
    minimumPackets: Long = 100L,
) {
    private val trend = MediaDecryptCostTrend(minimumPackets)
    private val before = arrayOfNulls<String>(24)
    private var next = 0
    private var count = 0
    private var after = 0

    fun record(summary: String, cpuNs: Long, bytes: Long, packets: Long, ended: Boolean = false) {
        val change = if (ended) null else trend.observe(cpuNs, bytes, packets)
        if (change != null) {
            report("Runtime media cost-change worker=$worker $change")
            for (offset in 0 until count) {
                emit("before", before[(next - count + offset + before.size) % before.size]!!)
            }
            emit("trigger", summary)
            after = 24
        } else if (after > 0) {
            emit("after", summary)
            after--
        }
        before[next] = summary
        next = (next + 1) % before.size
        count = minOf(count + 1, before.size)
    }

    private fun emit(phase: String, summary: String) =
        report("Runtime media cost-window worker=$worker phase=$phase $summary")
}
