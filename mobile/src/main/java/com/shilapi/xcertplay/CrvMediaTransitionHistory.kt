package com.shilapi.xcertplay

/** Writer-thread-only, bounded capture of existing summaries around OEM camera transitions. */
internal class CrvMediaTransitionHistory {
    private data class Sample(val id: Long, val atMs: Long, val message: String)
    private val before = java.util.ArrayDeque<Sample>()
    private var nextId = 0L
    private var capture = 0L
    private var startMs = Long.MIN_VALUE
    private var untilMs = Long.MIN_VALUE
    private var remaining = 0

    fun record(nowMs: Long, message: String): List<String> {
        val transition = "Video visibility=" in message
        val summary = transition || "Receive: " in message || "Audio processing " in message ||
            "Audio media " in message || "decoded output " in message ||
            "Runtime media tid=" in message || "video pipeline " in message ||
            "Video surface ready " in message || "Audio OEM " in message
        if (!summary) return emptyList()
        while (before.isNotEmpty() && nowMs - before.first().atMs > 30_000L) before.removeFirst()
        val result = mutableListOf<String>()
        fun emit(phase: String, sample: Sample) {
            if (remaining > 0) {
                remaining--
                result += "Runtime media transition-window capture=$capture phase=$phase " +
                    "sourceEvent=${sample.id} sourceElapsedMs=${sample.atMs} ${sample.message}"
            }
        }
        if (transition) {
            if (nowMs > untilMs) {
                capture++
                startMs = nowMs
                remaining = 192
                before.forEach { emit("before", it) }
            }
            // Merge rapid hide/show; neither duration nor emitted lines can grow without bound.
            untilMs = minOf(nowMs + 30_000L, startMs + 60_000L)
        }
        val sample = Sample(++nextId, nowMs, message.take(3000))
        if (nowMs <= untilMs) emit(if (transition) "trigger" else "after", sample)
        before.addLast(sample)
        while (before.size > 96) before.removeFirst()
        return result
    }
}
