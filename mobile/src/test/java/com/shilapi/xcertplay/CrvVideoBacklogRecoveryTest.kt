package com.shilapi.xcertplay

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CrvVideoBacklogRecoveryTest {
    private fun CrvVideoBacklogRecovery.sample(
        nowMs: Long,
        ageMs: Long,
        spanMs: Long,
        frames: Int = 3,
    ): Boolean = observe(
        nowNs = nowMs * MS,
        currentReceivedNs = (nowMs - ageMs) * MS,
        pendingFrames = frames,
        newestPendingReceivedNs = (nowMs - ageMs + spanMs) * MS,
    )

    @Test
    fun stableDelayedBurstIsAllowedToDrain() {
        val recovery = CrvVideoBacklogRecovery()
        for (now in listOf(0L, 750L, 1_500L, 3_000L)) {
            assertFalse(recovery.sample(now, ageMs = 400, spanMs = 200))
        }
    }

    @Test
    fun growingBacklogRecoversOnlyAfterGracePeriod() {
        val recovery = CrvVideoBacklogRecovery()
        assertFalse(recovery.sample(0, ageMs = 400, spanMs = 200))
        assertFalse(recovery.sample(749, ageMs = 400, spanMs = 300))
        assertTrue(recovery.sample(750, ageMs = 400, spanMs = 300))
    }

    @Test
    fun hardAgeRecoversWhenNewerFramesRemainQueued() {
        val recovery = CrvVideoBacklogRecovery()
        assertTrue(recovery.sample(2_000, ageMs = 1_500, spanMs = 100))
        assertFalse(recovery.sample(2_001, ageMs = 2_000, spanMs = 99))
    }

    @Test
    fun emptyOrInvalidBacklogDoesNotRecover() {
        val recovery = CrvVideoBacklogRecovery()
        assertFalse(recovery.observe(1_000 * MS, 500 * MS, 0, null))
        assertFalse(recovery.observe(1_000 * MS, 1_001 * MS, 3, 1_002 * MS))
    }

    @Test
    fun obsoleteRecoveryFrameNeedsBothHardAgeAndNewerQueuedVideo() {
        val now = 2_000_000_000L
        assertTrue(
            CrvVideoRecoveryFrameAge.isObsolete(
                nowNs = now,
                receivedNs = 500_000_000L,
                pendingFrames = 3,
                newestPendingReceivedNs = 600_000_000L,
            ),
        )
        assertFalse(
            CrvVideoRecoveryFrameAge.isObsolete(
                nowNs = now,
                receivedNs = 500_000_001L,
                pendingFrames = 3,
                newestPendingReceivedNs = 600_000_001L,
            ),
        )
        assertFalse(
            CrvVideoRecoveryFrameAge.isObsolete(
                nowNs = now,
                receivedNs = 0L,
                pendingFrames = 0,
                newestPendingReceivedNs = null,
            ),
        )
        assertFalse(
            CrvVideoRecoveryFrameAge.isObsolete(
                nowNs = now,
                receivedNs = 0L,
                pendingFrames = 3,
                newestPendingReceivedNs = 99_999_999L,
            ),
        )
    }

    private companion object {
        const val MS = 1_000_000L
    }
}
