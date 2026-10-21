package com.shilapi.xcertplay

/** Own only a mode change made by this session; never reset an OEM call's mode or SCO route. */
internal class CrvCommunicationModeLease(
    private val mode: () -> Int,
    private val setMode: (Int) -> Unit,
    private val sco: () -> Boolean,
) {
    private val owners = HashSet<String>()
    private var changed = false
    @Synchronized fun acquire(owner: String) {
        if (!owners.add(owner) || owners.size != 1) return
        if (mode() == 0 && !sco()) {
            setMode(3) // MODE_IN_COMMUNICATION, API11; no Bluetooth routing mutation.
            changed = mode() == 3
        }
    }
    @Synchronized fun release(owner: String) {
        owners.remove(owner)
        if (owners.isEmpty()) restore()
    }
    @Synchronized fun close() { owners.clear(); restore() }
    private fun restore() {
        if (changed && mode() == 3 && !sco()) setMode(0)
        changed = false
    }
}

internal object CrvAudioBufferPolicy {
    fun preferred(minimum: Int, bytesPerSecond: Int, music: Boolean): Int {
        require(minimum > 0 && bytesPerSecond > 0)
        return if (music) maxOf(minimum, 16384, bytesPerSecond * 220 / 1000)
        else maxOf(minimum, bytesPerSecond * 90 / 1000)
    }
}
