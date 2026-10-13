package com.shilapi.xcertplay

import org.junit.Assert.assertEquals
import org.junit.Test

class CrvAudioBufferProgressTest {
    @Test fun briefPacketGapDoesNotPauseOneHundredMillisecondsOfPlayableAudio() {
        val floor = CrvMediaRebufferPolicy.recoveryFloorBytes(57_600, 48_000, 4)
        assertEquals(5_760L, floor)
        assertEquals(false, CrvMediaRebufferPolicy.shouldPause(
            true, true, true, 900_000_000L, 1_000_000_000L,
            19_200L, floor, 80_000_000L))
        assertEquals(true, CrvMediaRebufferPolicy.shouldPause(
            true, true, true, 900_000_000L, 1_000_000_000L,
            4_800L, floor, 80_000_000L))
        assertEquals(2_000L, CrvMediaRebufferPolicy.recoveryFloorBytes(4_000, 48_000, 4))
    }

    @Test fun shortRecoveredTailResumesAfterFreshPcmAndLongerIdle() {
        fun resume(now: Long, fresh: Boolean = true, empty: Boolean = true) =
            CrvMediaTailResumePolicy.shouldResume(true, fresh, false, 8_000,
                empty, 1_000_000_000L, 1_000_000_000L, now)
        assertEquals(false, resume(1_599_000_000L))
        assertEquals(true, resume(1_600_000_000L))
        assertEquals(false, resume(2_000_000_000L, fresh = false))
        assertEquals(false, resume(2_000_000_000L, empty = false))
    }

    @Test fun initialTailKeepsShorterDelayAndRejectsEmptyOrAlreadyPlayingState() {
        assertEquals(true, CrvMediaTailResumePolicy.shouldResume(false, false, false,
            100, true, 1_000_000_000L, 1_000_000_000L, 1_180_000_000L))
        assertEquals(false, CrvMediaTailResumePolicy.shouldResume(false, false, true,
            100, true, 1_000_000_000L, 1_000_000_000L, 2_000_000_000L))
        assertEquals(false, CrvMediaTailResumePolicy.shouldResume(true, true, false,
            0, true, 1_000_000_000L, 1_000_000_000L, 2_000_000_000L))
        assertEquals(false, CrvMediaTailResumePolicy.shouldResume(true, true, false,
            100, true, 1_000_000_000L, 2_000_000_000L, 1_900_000_000L))
    }

    @Test fun resumedTailDrainsToCompletionInsteadOfPausingItsLastThirtyMilliseconds() {
        assertEquals(false, CrvMediaRebufferPolicy.shouldPause(
            true, true, true, 900_000_000L, 2_000_000_000L,
            3_840L, 5_760L, 80_000_000L, drainingTail = true))
        assertEquals(true, CrvMediaRebufferPolicy.shouldPause(
            true, true, true, 900_000_000L, 2_000_000_000L,
            3_840L, 5_760L, 80_000_000L, drainingTail = false))
    }
    @Test fun oversizedFirstPcmBlockStartsPlaybackBeforeWritingTheRemainder() {
        val bufferBytes = 16_000
        val primeBytes = 12_000
        val payloadBytes = 32_000
        var primed = 0
        var written = 0
        var playing = false
        var calls = 0
        while (written < payloadBytes) {
            val next = CrvAudioPrimeWriteSize.next(payloadBytes - written, playing, primeBytes, primed)
            if (!playing) {
                org.junit.Assert.assertTrue(next <= bufferBytes - primed)
                primed += next
            }
            written += next
            if (primed >= primeBytes) playing = true
            calls++
        }
        assertEquals(payloadBytes, written)
        assertEquals(2, calls)
        assertEquals(true, playing)
    }
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
