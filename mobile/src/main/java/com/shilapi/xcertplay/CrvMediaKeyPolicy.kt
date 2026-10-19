package com.shilapi.xcertplay

/** Deduplicate the same physical DOWN delivered through both Activity and media receiver. */
internal class CrvMediaKeyPolicy {
    private data class Press(val device: Int, val code: Int, val downTime: Long)
    private val recent = java.util.ArrayDeque<Press>()

    @Synchronized fun accept(device: Int, code: Int, downTime: Long, repeat: Int): Boolean {
        if (repeat != 0) return false
        val press = Press(device, code, downTime)
        if (recent.contains(press)) return false
        if (recent.size == 32) recent.removeFirst()
        recent.addLast(press)
        return true
    }
}
