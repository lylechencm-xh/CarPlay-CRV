package com.shilapi.xcertplay

/** Drop only already-decoded display outputs; compressed reference frames are still decoded. */
internal class CrvDecodedVideoOutput(private val release: (Int, Boolean) -> Unit) {
    private var pending = -1
    private var pendingPtsUs = 0L
    var discardedTotal = 0L; private set

    fun offer(index: Int, presentationTimeUs: Long = 0L) {
        require(index >= 0)
        val old = pending
        pending = index
        pendingPtsUs = presentationTimeUs
        if (old >= 0) {
            release(old, false)
            discardedTotal++
        }
    }

    fun finish(render: Boolean): Long? {
        val last = pending
        val ptsUs = pendingPtsUs
        pending = -1
        if (last < 0) return null
        release(last, render)
        return if (render) ptsUs else null
    }

    /** Codec flush/release owns pending buffers after an error. */
    fun reset() { pending = -1 }
}
