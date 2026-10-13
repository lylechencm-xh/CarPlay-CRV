package com.shilapi.xcertplay

import org.junit.Assert.*
import org.junit.Test

class CrvVideoOutputRecoveryTest {
    @Test fun emptyOutputPollsStillDetectPendingFramesAfterLastPicture() {
        val recovery = CrvVideoOutputRecovery()
        recovery.submitted(1_000_000L)
        assertFalse(recovery.observe(1_000_000_000L, 1_000_000L))
        recovery.submitted(2_000_000L)
        assertFalse(recovery.observe(2_000_000_000L, 0L))
        assertTrue(recovery.observe(2_750_000_000L, 0L))
        assertEquals("output stalled", recovery.reason)
    }

    @Test fun startupAllowsSlowCodecButEventuallyRecoversWithoutAnyOutput() {
        val recovery = CrvVideoOutputRecovery()
        recovery.submitted(1_000_000L)
        assertFalse(recovery.observe(4_999_000_000L, 0L))
        assertFalse(recovery.observe(5_000_000_000L, 0L))
        assertTrue(recovery.observe(5_750_000_000L, 0L))
        assertEquals("startup output timeout", recovery.reason)
    }

    @Test fun onePendingInterframeCanRecoverWithoutALargeInputSpan() {
        val recovery = CrvVideoOutputRecovery()
        recovery.submitted(1_000_000L)
        assertFalse(recovery.observe(1_000_000_000L, 1_000_000L))
        recovery.submitted(1_100_000L)
        assertFalse(recovery.observe(2_599_000_000L, 0L))
        assertFalse(recovery.observe(2_600_000_000L, 0L))
        assertTrue(recovery.observe(3_350_000_000L, 0L))
    }

    @Test fun fullyRenderedStaticScreenDoesNotRecoverOnEmptyPolls() {
        val recovery = CrvVideoOutputRecovery()
        recovery.submitted(1_000_000L)
        assertFalse(recovery.observe(3_000_000_000L, 1_000_000L))
        assertFalse(recovery.observe(10_000_000_000L, 0L))
        assertFalse(recovery.observe(11_000_000_000L, 0L))
        recovery.reset()
        assertFalse(recovery.observe(20_000_000_000L, 0L))
    }
    @Test fun persistentCodecLagRecoversWithEmptyApplicationQueue() {
        val recovery = CrvVideoOutputRecovery()
        recovery.submitted(2_000_000L)
        assertFalse(recovery.observe(2_000_000_000L, 1_000_000L))
        recovery.submitted(2_750_000L)
        assertTrue(recovery.observe(2_750_000_000L, 1_750_000L))
    }

    @Test fun staticScreenAndSlowIncomingFramesDoNotTriggerRecovery() {
        val recovery = CrvVideoOutputRecovery()
        recovery.submitted(1_000_000L)
        assertFalse(recovery.observe(5_000_000_000L, 1_000_000L))
        assertFalse(recovery.observe(6_000_000_000L, 1_000_000L))
        recovery.submitted(1_100_000L)
        assertFalse(recovery.observe(7_000_000_000L, 1_000_000L))
    }

    @Test fun shortBurstAndCaughtUpOutputResetTheGracePeriod() {
        val recovery = CrvVideoOutputRecovery()
        recovery.submitted(2_000_000L)
        assertFalse(recovery.observe(2_000_000_000L, 1_000_000L))
        assertFalse(recovery.observe(2_100_000_000L, 2_000_000L))
        recovery.submitted(3_000_000L)
        assertFalse(recovery.observe(3_000_000_000L, 2_000_000L))
        assertFalse(recovery.observe(3_700_000_000L, 2_000_000L))
        assertTrue(recovery.observe(3_750_000_000L, 2_000_000L))
    }

    @Test fun cooldownSurvivesFlushAndResetDiscardsOldTimestamps() {
        val recovery = CrvVideoOutputRecovery()
        recovery.submitted(2_000_000L)
        recovery.observe(2_000_000_000L, 1_000_000L)
        assertTrue(recovery.observe(2_750_000_000L, 1_000_000L))
        recovery.reset()
        assertFalse(recovery.observe(4_000_000_000L, 1_000_000L))
        recovery.submitted(4_000_000L)
        assertFalse(recovery.observe(4_000_000_000L, 3_000_000L))
        assertFalse(recovery.observe(5_750_000_000L, 3_000_000L))
        assertTrue(recovery.observe(6_500_000_000L, 3_000_000L))
    }
}
