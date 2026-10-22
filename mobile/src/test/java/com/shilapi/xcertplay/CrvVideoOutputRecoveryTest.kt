package com.shilapi.xcertplay

import org.junit.Assert.*
import org.junit.Test

class CrvVideoOutputRecoveryTest {
    @Test fun steadyLatencyWithEmptyPollsDoesNotFlushHealthyOutput() {
        val recovery = CrvVideoOutputRecovery()
        // Approximately 7 fps with 600 ms of steady pipeline latency for 30 seconds.
        for (step in 0..6000) {
            val nowNs = 2_000_000_000L + step * 5_000_000L
            val nowUs = nowNs / 1000L
            if (step % 28 == 0) {
                recovery.submitted(nowUs, nowNs)
                assertFalse(recovery.observe(nowNs, nowUs - 600_000L))
            } else {
                assertFalse(recovery.observe(nowNs, 0L))
            }
        }
    }

    @Test fun realOutputSilenceRequiresGraceBeforeRecovery() {
        val recovery = CrvVideoOutputRecovery()
        recovery.submitted(1_000_000L)
        assertFalse(recovery.observe(1_000_000_000L, 1_000_000L))
        recovery.submitted(2_000_000L)
        assertFalse(recovery.observe(2_499_000_000L, 0L))
        assertFalse(recovery.observe(2_500_000_000L, 0L))
        assertFalse(recovery.observe(3_249_000_000L, 0L))
        assertTrue(recovery.observe(3_250_000_000L, 0L))
        assertEquals("output stalled", recovery.reason)
    }

    @Test fun startupTimeoutStartsAtSubmissionRatherThanOldPacketTimestamp() {
        val recovery = CrvVideoOutputRecovery()
        recovery.submitted(1_000_000L, 3_000_000_000L)
        assertFalse(recovery.observe(6_999_000_000L, 0L))
        assertFalse(recovery.observe(7_000_000_000L, 0L))
        assertTrue(recovery.observe(7_750_000_000L, 0L))
        assertEquals("startup output timeout", recovery.reason)
        assertTrue(recovery.triggerSnapshot.contains("outputPtsUs=0 pendingSpanUs=unknown"))
    }

    @Test fun onePendingInterframeGetsALongerTimeoutButEventuallyRecovers() {
        val recovery = CrvVideoOutputRecovery()
        recovery.submitted(1_000_000L)
        assertFalse(recovery.observe(1_000_000_000L, 1_000_000L))
        recovery.submitted(1_100_000L)
        assertFalse(recovery.observe(5_099_000_000L, 0L))
        assertFalse(recovery.observe(5_100_000_000L, 0L))
        assertTrue(recovery.observe(5_850_000_000L, 0L))
        assertFalse(recovery.observe(30_000_000_000L, 0L))
    }

    @Test fun fullyRenderedStaticScreenNeverRecovers() {
        val recovery = CrvVideoOutputRecovery()
        recovery.submitted(1_000_000L)
        assertFalse(recovery.observe(3_000_000_000L, 1_000_000L))
        assertFalse(recovery.observe(10_000_000_000L, 0L))
        assertFalse(recovery.observe(11_000_000_000L, 0L))
        recovery.reset()
        assertFalse(recovery.observe(20_000_000_000L, 0L))
    }

    @Test fun severeBacklogRecoversEvenWhenOutputContinues() {
        val recovery = CrvVideoOutputRecovery()
        recovery.submitted(3_000_000L)
        assertFalse(recovery.observe(3_000_000_000L, 1_000_000L))
        recovery.submitted(3_750_000L)
        assertTrue(recovery.observe(3_750_000_000L, 1_750_000L))
        assertEquals("output lag persisted", recovery.reason)
    }

    @Test fun resumedOutputCancelsStallGrace() {
        val recovery = CrvVideoOutputRecovery()
        recovery.submitted(1_000_000L)
        recovery.observe(1_000_000_000L, 1_000_000L)
        recovery.submitted(2_000_000L)
        assertFalse(recovery.observe(2_500_000_000L, 0L))
        assertFalse(recovery.observe(3_200_000_000L, 1_500_000L))
        assertFalse(recovery.observe(3_250_000_000L, 0L))
        assertFalse(recovery.observe(4_700_000_000L, 0L))
        assertTrue(recovery.observe(5_450_000_000L, 0L))
    }

    @Test fun caughtUpOutputCancelsSevereBacklogGrace() {
        val recovery = CrvVideoOutputRecovery()
        recovery.submitted(3_000_000L)
        assertFalse(recovery.observe(3_000_000_000L, 1_000_000L))
        assertFalse(recovery.observe(3_700_000_000L, 3_000_000L))
        recovery.submitted(5_000_000L)
        assertFalse(recovery.observe(5_000_000_000L, 3_000_000L))
        assertFalse(recovery.observe(5_700_000_000L, 3_500_000L))
        assertTrue(recovery.observe(5_750_000_000L, 3_500_000L))
    }

    @Test fun cooldownSurvivesReset() {
        val recovery = CrvVideoOutputRecovery()
        recovery.submitted(3_000_000L)
        recovery.observe(3_000_000_000L, 1_000_000L)
        recovery.submitted(3_750_000L)
        assertTrue(recovery.observe(3_750_000_000L, 1_750_000L))
        recovery.reset()
        recovery.submitted(5_000_000L)
        assertFalse(recovery.observe(5_000_000_000L, 3_000_000L))
        assertFalse(recovery.observe(6_749_000_000L, 4_500_000L))
        recovery.submitted(6_750_000L)
        assertFalse(recovery.observe(6_750_000_000L, 4_500_000L))
        recovery.submitted(7_500_000L)
        assertTrue(recovery.observe(7_500_000_000L, 5_500_000L))
    }

    @Test fun invalidFutureTimestampDoesNotStartRecovery() {
        val recovery = CrvVideoOutputRecovery()
        recovery.submitted(3_000_000L)
        assertFalse(recovery.observe(2_000_000_000L, 1_000_000L))
        assertFalse(recovery.observe(3_000_000_000L, 4_000_000L))
    }
}
