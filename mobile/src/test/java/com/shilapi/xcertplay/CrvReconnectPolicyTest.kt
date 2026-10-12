package com.shilapi.xcertplay

import org.junit.Assert.*
import org.junit.Test

class CrvReconnectPolicyTest {
    @Test fun lockedPhoneDoesNotUseTransientRetryBudget() {
        val failure = classifyCrvReconnectFailure(IllegalStateException("wrapper",
            IllegalStateException("The iPhone is password protected")))
        val decision = crvReconnectDecision(failure, 4)
        assertEquals(CrvReconnectFailure.PHONE_LOCKED, failure)
        assertEquals(15_000L, decision.delayMillis)
        assertFalse(decision.consumesAttempt)
    }

    @Test fun deniedTrustMissingUsbAndPermissionWaitForUserOrAttach() {
        for (message in listOf("The user denied Lockdown pairing", "iPhone USB detached during NCM read",
            "VPN permission revoked", "Lockdown pairing timed out")) {
            val decision = crvReconnectDecision(classifyCrvReconnectFailure(Exception(message)), 0)
            assertNull(decision.delayMillis)
            assertFalse(decision.consumesAttempt)
        }
    }

    @Test fun transientFailuresBackOffAndStopAfterFourAttempts() {
        assertEquals(listOf(1_000L, 2_000L, 4_000L, 8_000L),
            (0..3).map { crvReconnectDecision(CrvReconnectFailure.TRANSIENT, it).delayMillis })
        assertNull(crvReconnectDecision(CrvReconnectFailure.TRANSIENT, 4).delayMillis)
    }

    @Test fun retiringConnectionInvalidatesQueuedCallbacks() {
        val epochs = CrvConnectionEpoch()
        val old = epochs.advance()
        assertTrue(epochs.isCurrent(old))
        val replacement = epochs.advance()
        assertFalse(epochs.isCurrent(old))
        assertTrue(epochs.isCurrent(replacement))
    }

    @Test fun briefOrInterruptedSessionDoesNotResetRetryBudget() {
        val stability = CrvSessionStability()
        stability.active(1, 1_000L)
        assertFalse(stability.isStable(1, 60_999L))
        stability.active(1, 60_999L)
        assertTrue(stability.isStable(1, 61_000L))
        assertFalse(stability.isStable(2, 61_000L))
        stability.clear()
        assertFalse(stability.isStable(1, 100_000L))
        stability.active(1, 100_000L)
        assertFalse(stability.isStable(1, 101_000L))
    }

    @Test fun latestConnectionRequestWaitsForAllUsbCleanup() {
        val gate = CrvCleanupGate()
        val calls = mutableListOf<String>()
        gate.begin()
        gate.begin()
        assertTrue(gate.defer { calls += "obsolete mode" })
        assertTrue(gate.defer { calls += "latest mode" })
        assertNull(gate.complete())
        assertTrue(calls.isEmpty())
        gate.complete()?.invoke()
        assertEquals(listOf("latest mode"), calls)
        assertFalse(gate.defer { fail("no cleanup pending") })
    }

    @Test fun manualDisconnectCancelsDeferredConnectAndWaitingHintsAreVisible() {
        val gate = CrvCleanupGate()
        gate.begin()
        gate.defer { fail("cancelled connection must not start") }
        gate.clearDeferred()
        assertNull(gate.complete())
        for (failure in CrvReconnectFailure.values().filter { it != CrvReconnectFailure.TRANSIENT }) {
            assertNotNull(crvRecoveryCopy(crvReconnectDecision(failure, 0).message))
        }
    }
}
