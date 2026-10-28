package com.shilapi.xcertplay.airplay

import android.os.Process
import java.io.Closeable
import java.io.File

/** Bounded, read-only sampling stays off the media workers and tolerates OEM permissions. */
internal class MediaWorkerDiagnostics(
    private val tid: () -> Int,
    private val visible: () -> Boolean,
    private val report: (String) -> Unit,
    private val label: String = "video-decrypt",
) : Closeable {
    @Volatile private var closed = false
    private val worker = Thread({
        runCatching { Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND) }
        var failures = 0L
        var samples = 0L
        fun emit(message: String) { runCatching { report(message) } }
        emit("Runtime media sampler started worker=$label")
        try {
            while (!closed) {
                val currentTid = tid()
                if (currentTid > 0) runCatching {
                    val runtime = Runtime.getRuntime()
                    val checkpoint = samples++ % 6L == 0L
                    report(MediaWorkerSnapshot.collect(currentTid, visible(), ::readOne) +
                        " worker=$label checkpoint=$checkpoint " +
                        "heapUsedKiB=${(runtime.totalMemory() - runtime.freeMemory()) / 1024L} " +
                        MediaRuntimeSnapshot.collect())
                }.onFailure {
                    failures++
                    if (failures == 1L || failures % 6L == 0L)
                        emit("Runtime media sampler failed worker=$label count=$failures error=${it.javaClass.simpleName}")
                }
                Thread.sleep(10_000L)
            }
        } catch (_: InterruptedException) { /* Stream retired. */ }
        finally { emit("Runtime media sampler stopped worker=$label failures=$failures") }
    }, "airplay-media-stats").apply { isDaemon = true }

    fun start() = worker.start()
    override fun close() { closed = true; worker.interrupt() }

    private fun readOne(path: String): String? = runCatching {
        File(path).bufferedReader().use { it.readLine()?.take(512) }
    }.getOrNull()
}

/** Public runtime counters are optional on API17; never infer zero from an unavailable API. */
internal object MediaRuntimeSnapshot {
    private val statsMethod = runCatching { android.os.Debug::class.java.getMethod("getRuntimeStats") }.getOrNull()
    fun collect(): String {
        val stats = runCatching { statsMethod?.invoke(null) as? Map<*, *> }.getOrNull()
        fun counter(name: String) = stats?.get("art.gc.$name")?.toString()?.toLongOrNull()?.toString() ?: "unknown"
        return "vm=${(System.getProperty("java.vm.name") ?: "unknown").replace(' ', '_')} " +
            "gcCount=${counter("gc-count")} gcTimeMs=${counter("gc-time")} " +
            "allocatedBytes=${counter("bytes-allocated")} gcBlockingCount=${counter("blocking-gc-count")}"
    }
}

internal object MediaWorkerSnapshot {
    fun collect(tid: Int, visible: Boolean, read: (String) -> String?): String {
        val stat = read("/proc/self/task/$tid/stat").orEmpty()
        // comm may contain spaces or ')'; fields after its final ')' start at field 3.
        val end = stat.lastIndexOf(')')
        val fields = if (end >= 0) stat.substring(end + 1).trim().split(Regex("\\s+")) else emptyList()
        val cpu = fields.getOrNull(36)?.toIntOrNull()?.takeIf { it in 0..255 }
        val nice = fields.getOrNull(16)?.toIntOrNull()
        val frequency = cpu?.let {
            read("/sys/devices/system/cpu/cpu$it/cpufreq/scaling_cur_freq")?.trim()?.toLongOrNull()
        }
        val scheduler = read("/proc/self/task/$tid/schedstat")?.trim()?.split(Regex("\\s+"))
        fun sched(index: Int) = scheduler?.getOrNull(index)?.toLongOrNull()?.toString() ?: "unknown"
        val thermal = (0..3).joinToString(",") { zone ->
            val temp = read("/sys/class/thermal/thermal_zone$zone/temp")?.trim()?.toLongOrNull()
            "$zone:${temp ?: "unknown"}"
        }
        return "Runtime media tid=$tid visible=$visible cpu=${cpu ?: "unknown"} " +
            "freqKHz=${frequency ?: "unknown"} nice=${nice ?: "unknown"} " +
            "schedRunNs=${sched(0)} schedWaitNs=${sched(1)} schedSlices=${sched(2)} " +
            "thermalRaw=[$thermal]"
    }
}
