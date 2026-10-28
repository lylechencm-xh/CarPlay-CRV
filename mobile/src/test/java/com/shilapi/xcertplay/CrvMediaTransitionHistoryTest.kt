package com.shilapi.xcertplay

import org.junit.Assert.*
import org.junit.Test

class CrvMediaTransitionHistoryTest {
    @Test fun capturesBeforeAndAfterAndMergesCameraRoundTrip() {
        val history = CrvMediaTransitionHistory()
        assertTrue(history.record(0L, "Audio processing type=100 streamId=1 expired=0").isEmpty())
        val hide = history.record(1000L, "[ACTIVE] Video visibility=false transition=1")
        assertEquals(2, hide.size)
        assertTrue(hide[0].contains("phase=before sourceEvent=1 sourceElapsedMs=0"))
        assertTrue(hide[1].contains("phase=trigger"))
        val audio = history.record(2000L, "Audio processing type=100 streamId=1 expired=1")
        assertTrue(audio.single().contains("capture=1 phase=after"))
        val show = history.record(3000L, "Video visibility=true transition=2")
        assertEquals(1, show.size)
        assertTrue(show.single().contains("capture=1"))
        assertTrue(history.record(33_001L, "Receive: audio packets=10").isEmpty())
    }

    @Test fun captureIsBoundedAndClassifiedAsCriticalOverviewEvidence() {
        val history = CrvMediaTransitionHistory()
        repeat(200) { history.record(it.toLong(), "Receive: audio packets=$it") }
        val archived = history.record(1000L, "Video visibility=false transition=1")
        assertEquals(97, archived.size)
        var emitted = archived.size
        repeat(500) { emitted += history.record(2000L + it, "Audio media rebuffer count=$it").size }
        assertEquals(192, emitted)
        archived.forEach {
            assertTrue(CrvLogCategory.isCritical(it))
            assertTrue(CrvLogCategory.includeInOverview(CrvLogCategory.SYSTEM, it, false))
        }
    }

    @Test fun recoveryHistorySurvivesReplacementIsBoundedAndReplaysSameIds() {
        val history = CrvVideoRecoveryHistory()
        repeat(20) { history.record("event=${it + 1} instance=$it outcome=flushed") }
        val replay = history.replay(60_000_000_000L)
        assertEquals(16, replay.size)
        assertTrue(replay.first().contains("event=5 "))
        assertTrue(history.replay(61_000_000_000L).isEmpty())
        assertEquals(replay, history.replay(62_000_000_000L, force = true))
        replay.forEach { assertTrue(CrvLogCategory.isCritical(it)) }
    }
}
