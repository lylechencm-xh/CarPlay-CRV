package com.shilapi.xcertplay

import org.junit.Assert.assertEquals
import org.junit.Test

class CrvCodecDrainScheduleTest {
    @Test fun waitsBrieflyWhileDecoderHasWorkAndSleepsNormallyWhenIdle() {
        val schedule = CrvCodecDrainSchedule()
        assertEquals(20L, schedule.queueWaitMillis(0L))
        schedule.submitted(0L)
        assertEquals(5L, schedule.queueWaitMillis(1_000_000L))
        schedule.outputReady()
        assertEquals(20L, schedule.queueWaitMillis(2_000_000L))
    }

    @Test fun oneOutputDoesNotHideOtherPendingFrames() {
        val schedule = CrvCodecDrainSchedule()
        schedule.submitted(0L)
        schedule.submitted(1_000_000L)
        schedule.outputReady()
        assertEquals(5L, schedule.queueWaitMillis(2_000_000L))
        schedule.outputReady()
        assertEquals(20L, schedule.queueWaitMillis(3_000_000L))
    }

    @Test fun stalledCodecStopsFastPollingAndFlushClearsOldWork() {
        val schedule = CrvCodecDrainSchedule()
        schedule.submitted(0L)
        assertEquals(20L, schedule.queueWaitMillis(250_000_000L))
        schedule.submitted(300_000_000L)
        assertEquals(5L, schedule.queueWaitMillis(301_000_000L))
        schedule.reset()
        assertEquals(20L, schedule.queueWaitMillis(302_000_000L))
        schedule.outputReady()
        assertEquals(20L, schedule.queueWaitMillis(303_000_000L))
    }
}
