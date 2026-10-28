package com.shilapi.xcertplay.airplay

/** Configuration is captured when a stream starts; existing streams never change mode mid-packet. */
object MediaCryptoDiagnostics {
    enum class Sampling { OFF, FIXED, JITTERED }
    @Volatile var sampling: Sampling = Sampling.JITTERED
        private set

    fun configure(mode: String?) {
        sampling = when (mode) {
            "off" -> Sampling.OFF
            "fixed" -> Sampling.FIXED
            else -> Sampling.JITTERED
        }
    }
}

/** Worker-owned, bounded sampling. No payload retention or per-call sampling allocations. */
internal class MediaDecryptProfiler(
    private val label: String,
    private val report: (String) -> Unit,
    private val cpuNs: () -> Long = android.os.Debug::threadCpuTimeNanos,
    private val nowNs: () -> Long = System::nanoTime,
    private val mode: MediaCryptoDiagnostics.Sampling = MediaCryptoDiagnostics.sampling,
    private val seed: Int = label.hashCode(),
) {
    private var calls = 0L
    private var samples = 0L
    private var bytes = 0L
    // init, allocate, prefix copy, cipher process, authenticate, trim
    private val totals = LongArray(6)
    private val current = LongArray(6)
    private var mark = 0L
    private var stage = 0
    private var active = false
    private var failures = 0L
    private var randomState = if (seed == 0) 1 else seed
    private var sampleSlot = 0
    private var intervalStartNs = 0L
    private var intervalStartCall = 0L
    private var reportedAt = 0L
    private var first = true

    fun begin(): Boolean {
        val call = calls++
        if (mode == MediaCryptoDiagnostics.Sampling.OFF) return false
        val slot = (call % 128L).toInt()
        if (slot == 0 && call != 0L && mode == MediaCryptoDiagnostics.Sampling.JITTERED) {
            var value = randomState
            value = value xor (value shl 13)
            value = value xor (value ushr 17)
            value = value xor (value shl 5)
            randomState = value
            sampleSlot = value and 127
        }
        if (slot != sampleSlot) return false
        if (intervalStartNs == 0L) intervalStartNs = nowNs()
        stage = 0
        active = true
        mark = cpuNs()
        return true
    }

    private fun checkpoint() {
        check(active && stage < current.size)
        val now = cpuNs()
        current[stage++] = (now - mark).coerceAtLeast(0L)
        mark = now
    }
    fun initialized() = checkpoint()
    fun allocated() = checkpoint()
    fun prefixed() = checkpoint()
    fun processed() = checkpoint()
    fun authenticated() = checkpoint()

    fun failed() {
        if (!active) return
        active = false
        failures++
        // Partial stages are excluded from successful sample totals.
    }

    fun finished(length: Int) {
        checkpoint()
        check(stage == current.size)
        active = false
        for (index in totals.indices) totals[index] += current[index]
        bytes += length
        samples++
        val now = nowNs()
        if (!first && now - reportedAt < 30_000_000_000L) return
        runCatching { report("Runtime media crypto worker=$label implementation=bc-lightweight-ChaCha20Poly1305 " +
            "schema=2 startup=$first sampleEvery=128 sampling=$mode seed=$seed " +
            "fromNs=$intervalStartNs toNs=$now fromCall=$intervalStartCall toCall=$calls " +
            "samples=$samples sealedBytes=$bytes failedSamples=$failures initCpuUs=${totals[0] / 1000} " +
            "allocateCpuUs=${totals[1] / 1000} prefixCpuUs=${totals[2] / 1000} " +
            "processCpuUs=${totals[3] / 1000} finalCpuUs=${totals[4] / 1000} " +
            "trimCpuUs=${totals[5] / 1000} totalCpuUs=${totals.sum() / 1000}") }
        samples = 0; bytes = 0; totals.fill(0L); failures = 0
        intervalStartNs = now
        intervalStartCall = calls
        reportedAt = now
        first = false
    }
}

/** Compare sustained CPU per byte, rather than packet rate or scheduling delay. */
internal class MediaDecryptCostTrend(private val minimumPackets: Long = 100L) {
    private var baselineWindows = 0
    private var baselineCpu = 0.0
    private var baselineBytes = 0.0
    private var highWindows = 0
    private var alerted = false

    fun observe(cpuNs: Long, bytes: Long, packets: Long): String? {
        if (packets < minimumPackets || bytes <= 0 || cpuNs <= 0) { highWindows = 0; return null }
        if (baselineWindows < 3) {
            baselineWindows++; baselineCpu += cpuNs; baselineBytes += bytes
            return null
        }
        val ratio = cpuNs.toDouble() / bytes / (baselineCpu / baselineBytes)
        highWindows = if (ratio >= 1.8) highWindows + 1 else 0
        if (highWindows < 3 || alerted) return null
        alerted = true
        return "baselineCpuNsPerByte=${(baselineCpu / baselineBytes).toLong()} " +
            "currentCpuNsPerByte=${cpuNs / bytes} sustainedWindows=$highWindows"
    }
}
