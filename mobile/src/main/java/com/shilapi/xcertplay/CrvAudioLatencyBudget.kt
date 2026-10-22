package com.shilapi.xcertplay

/** One UDP-to-renderer queue budget; AudioTrack's PCM inventory is reported separately. */
internal object CrvAudioLatencyBudget {
    // Music can survive a short scheduling pause before authentication without gaining a
    // second 200ms allowance afterward. Keep interactive speech's receive deadline short.
    fun receiveNs(music: Boolean): Long = if (music) 160_000_000L else 80_000_000L
    fun totalQueueNs(music: Boolean): Long = if (music) 200_000_000L else 100_000_000L
}
