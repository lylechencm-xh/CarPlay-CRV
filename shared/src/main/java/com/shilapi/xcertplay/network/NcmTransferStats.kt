package com.shilapi.xcertplay.network

/** One instance per bridge direction; no payloads or addresses in diagnostic output. */
internal class NcmTransferStats(
    private val direction: String,
    private val report: (String) -> Unit,
    private val nowNs: () -> Long = System::nanoTime,
) {
    private var windowAt = nowNs()
    private var packets = 0L
    private var bytes = 0L
    private var failures = 0L
    private var maxNs = 0L

    fun transferred(size: Int, durationNs: Long, success: Boolean = true) {
        if (success) { packets++; bytes += size } else failures++
        maxNs = maxOf(maxNs, durationNs)
        flush()
    }

    fun flush(ended: Boolean = false) {
        val now = nowNs()
        if (!ended && now - windowAt < 5_000_000_000L) return
        runCatching { report("NCM transfer direction=$direction packets=$packets bytes=$bytes " +
            "failures=$failures writeMaxUs=${maxNs / 1000} windowMs=${(now - windowAt) / 1_000_000} ended=$ended") }
        windowAt = now
        packets = 0L
        bytes = 0L
        failures = 0L
        maxNs = 0L
    }
}
