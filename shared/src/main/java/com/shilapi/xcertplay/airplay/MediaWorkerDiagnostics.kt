package com.shilapi.xcertplay.airplay

import android.os.Process
import java.io.Closeable
import java.io.File

/** Bounded, read-only sampling stays off the media workers and tolerates OEM permissions. */
internal class MediaWorkerDiagnostics(
    private val tid: () -> Int,
    private val visible: () -> Boolean,
    private val report: (String) -> Unit,
) : Closeable {
    @Volatile private var closed = false
    private val worker = Thread({
        runCatching { Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND) }
        try {
            while (!closed) {
                val currentTid = tid()
                if (currentTid > 0) runCatching {
                    val runtime = Runtime.getRuntime()
                    report(MediaWorkerSnapshot.collect(currentTid, visible(), ::readOne) +
                        " heapUsedKiB=${(runtime.totalMemory() - runtime.freeMemory()) / 1024L}")
                }
                Thread.sleep(10_000L)
            }
        } catch (_: InterruptedException) { /* Stream retired. */ }
    }, "airplay-media-stats").apply { isDaemon = true }

    fun start() = worker.start()
    override fun close() { closed = true; worker.interrupt() }

    private fun readOne(path: String): String? = runCatching {
        File(path).bufferedReader().use { it.readLine()?.take(512) }
    }.getOrNull()
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
