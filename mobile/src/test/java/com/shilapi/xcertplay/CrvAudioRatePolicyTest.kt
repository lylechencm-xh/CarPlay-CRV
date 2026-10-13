package com.shilapi.xcertplay

import org.junit.Assert.*
import org.junit.Test

class CrvAudioRatePolicyTest {
    @Test fun sustainedBacklogRampsAndNeverExceedsHalfPercent() {
        val policy = CrvAudioRatePolicy(48_000)
        assertEquals(48_000, policy.desiredRate(0L, 600L, true))
        assertEquals(48_000, policy.desiredRate(1_500_000_000L, 600L, true))
        assertEquals(48_048, policy.desiredRate(2_000_000_000L, 600L, true))
        assertEquals(48_048, policy.desiredRate(2_100_000_000L, 600L, true))
        for (i in 5L..20L) {
            assertTrue(policy.desiredRate(i * 500_000_000L, 600L, true) <= 48_240)
        }
        assertEquals(48_240, policy.desiredRate(11_000_000_000L, 200L, true))
        assertEquals(48_192, policy.desiredRate(11_500_000_000L, 100L, true))
    }

    @Test fun transientBurstAndPrimingStayAtNominalSpeed() {
        val policy = CrvAudioRatePolicy(48_000)
        assertEquals(48_000, policy.desiredRate(0L, 600L, false))
        assertEquals(48_000, policy.desiredRate(1_000_000_000L, 600L, true))
        assertEquals(48_000, policy.desiredRate(2_000_000_000L, 200L, true))
        assertEquals(48_000, policy.desiredRate(3_000_000_000L, 600L, true))
        assertEquals(48_000, policy.desiredRate(4_500_000_000L, 600L, true))
    }

    @Test fun starvationRestoresNominalRateAndRequiresFreshGracePeriod() {
        val policy = CrvAudioRatePolicy(48_000)
        policy.desiredRate(0L, 600L, true)
        assertEquals(48_048, policy.desiredRate(2_000_000_000L, 600L, true))
        assertEquals(48_000, policy.desiredRate(2_100_000_000L, 600L, false))
        assertEquals(48_000, policy.desiredRate(3_000_000_000L, 600L, true))
        assertEquals(48_000, policy.desiredRate(4_500_000_000L, 600L, true))
    }
}
