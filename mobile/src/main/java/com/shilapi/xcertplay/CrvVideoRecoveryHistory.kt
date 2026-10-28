package com.shilapi.xcertplay

/** Sink-scoped history survives decoder replacement; replayed IDs are not new recoveries. */
internal class CrvVideoRecoveryHistory {
    private val events = java.util.ArrayDeque<String>()
    private var replayAtNs = 0L

    @Synchronized fun record(event: String) {
        events.addLast(event.take(3000))
        while (events.size > 16) events.removeFirst()
    }

    @Synchronized fun replay(nowNs: Long, force: Boolean = false): List<String> {
        if (!force && nowNs - replayAtNs < 60_000_000_000L) return emptyList()
        replayAtNs = nowNs
        return events.map { "Video recovery-history replay=true $it" }
    }
}
