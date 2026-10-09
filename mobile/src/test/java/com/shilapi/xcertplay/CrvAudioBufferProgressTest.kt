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
    fun mediaRebufferRequiresGapEmptyQueueAndLowHardwareBuffer() {
        val now = 1_000_000_000L
        val args = mapOf(
            "lastPacketNs" to 800_000_000L,
            "nowNs" to now,
            "queuedBytes" to 500L,
            "floor" to 600L,
            "gap" to 100_000_000L,
        )
        assertEquals(
            true,
            CrvMediaRebufferPolicy.shouldPause(
                isMedia = true,
                playbackStarted = true,
                compressedQueueEmpty = true,
                lastPacketNs = args.getValue("lastPacketNs"),
                nowNs = args.getValue("nowNs"),
                queuedBytes = args.getValue("queuedBytes"),
                recoveryFloorBytes = args.getValue("floor"),
                minimumPacketGapNs = args.getValue("gap"),
            ),
        )
        assertEquals(
            false,
            CrvMediaRebufferPolicy.shouldPause(
                isMedia = false,
                playbackStarted = true,
                compressedQueueEmpty = true,
                lastPacketNs = 800_000_000L,
                nowNs = now,
                queuedBytes = 500L,
                recoveryFloorBytes = 600L,
                minimumPacketGapNs = 100_000_000L,
            ),
        )
        assertEquals(
            false,
            CrvMediaRebufferPolicy.shouldPause(
                isMedia = true,
                playbackStarted = true,
                compressedQueueEmpty = false,
                lastPacketNs = 800_000_000L,
                nowNs = now,
                queuedBytes = 500L,
                recoveryFloorBytes = 600L,
                minimumPacketGapNs = 100_000_000L,
            ),
        )
        assertEquals(
            false,
            CrvMediaRebufferPolicy.shouldPause(
                isMedia = true,
                playbackStarted = true,
                compressedQueueEmpty = true,
                lastPacketNs = 950_000_000L,
                nowNs = now,
                queuedBytes = 500L,
                recoveryFloorBytes = 600L,
                minimumPacketGapNs = 100_000_000L,
            ),
        )
        assertEquals(
            false,
            CrvMediaRebufferPolicy.shouldPause(
                isMedia = true,
                playbackStarted = true,
                compressedQueueEmpty = true,
                lastPacketNs = 800_000_000L,
                nowNs = now,
                queuedBytes = 601L,
                recoveryFloorBytes = 600L,
                minimumPacketGapNs = 100_000_000L,
            ),
        )
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
