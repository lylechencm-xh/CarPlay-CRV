package com.shilapi.xcertplay

/** Protocol events, never inferred from diagnostic text. Callbacks are fenced by attempt epoch. */
enum class CrvConnectionEvent { ACTIVE, VIDEO_RESET, VIDEO_RENDERED, SESSION_ENDED, FAILED }

internal class CrvConnectionPresentation {
    var active = false; private set
    var rendered = false; private set
    private var firstRenderedMs: Long? = null
    private var lastRenderedMs: Long? = null

    fun accept(event: CrvConnectionEvent, nowMs: Long) {
        when (event) {
            CrvConnectionEvent.ACTIVE -> active = true
            CrvConnectionEvent.VIDEO_RESET -> {
                rendered = false
                firstRenderedMs = null
                lastRenderedMs = null
            }
            CrvConnectionEvent.VIDEO_RENDERED -> {
                rendered = true
                if (firstRenderedMs == null) firstRenderedMs = nowMs
                lastRenderedMs = nowMs
            }
            CrvConnectionEvent.SESSION_ENDED, CrvConnectionEvent.FAILED -> clear()
        }
    }

    fun shouldShowVideo() = active && rendered
    fun stable(nowMs: Long): Boolean = active && firstRenderedMs?.let {
        nowMs - it >= 60_000L && nowMs - (lastRenderedMs ?: it) <= 5_000L
    } == true

    fun clear() {
        active = false
        rendered = false
        firstRenderedMs = null
        lastRenderedMs = null
    }
}

internal fun ownsCrvSessionEnd(active: Any?, ended: Any): Boolean = active === ended
