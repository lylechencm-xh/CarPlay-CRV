package com.shilapi.xcertplay

import org.junit.Assert.assertEquals
import org.junit.Test

class CrvAudioBufferProgressTest {
    @Test
    fun tracksQueuedPcmAndPlaybackHeadAdvance() {
        val progress = CrvAudioBufferProgress(frameBytes = 4)
        progress.written(4_000)
        assertEquals(4_000L, progress.queuedBytes(0))
        assertEquals(2_000L, progress.queuedBytes(500))
        assertEquals(0L, progress.queuedBytes(1_000))
    }

    @Test
    fun playbackHeadWrapDoesNotCreateNegativeQueue() {
        val progress = CrvAudioBufferProgress(frameBytes = 2)
        repeat(8) { progress.written(1_073_741_824) }
        progress.written(40)
        assertEquals(42L, progress.queuedBytes(-1))
        assertEquals(40L, progress.queuedBytes(0))
        assertEquals(0L, progress.queuedBytes(20))
    }
}
