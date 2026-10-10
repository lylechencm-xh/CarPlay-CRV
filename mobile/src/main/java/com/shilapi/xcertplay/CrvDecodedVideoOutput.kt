package com.shilapi.xcertplay

/** Drop only already-decoded display outputs; compressed reference frames are still decoded. */
internal class CrvDecodedVideoOutput(private val release: (Int, Boolean) -> Unit) {
    private var pending = -1
    var discardedTotal = 0L; private set

    fun offer(index: Int) {
        require(index >= 0)
        val old = pending
        pending = index
        if (old >= 0) {
            release(old, false)
            discardedTotal++
        }
    }

    fun finish(render: Boolean) {
        val last = pending
        pending = -1
        if (last >= 0) release(last, render)
    }

    /** Codec flush/release owns pending buffers after an error. */
    fun reset() { pending = -1 }
}
