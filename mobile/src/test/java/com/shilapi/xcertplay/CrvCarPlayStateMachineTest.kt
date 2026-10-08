package com.shilapi.xcertplay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CrvCarPlayStateMachineTest {
    @Test fun wiredLifecycleIsOrderedAndSessionActivityIsIndependent() {
        val transitions = mutableListOf<CrvControllerTransition>()
        val state = CrvCarPlayStateMachine(transitions::add)

        state.start()
        state.ncmReady()
        state.usbMuxReady()
        state.lockdownReady()
        state.iap2Ready()
        state.networkReady()
        assertTrue(state.sessionActive())
        state.authenticated()
        state.sessionControlStarted()
        assertTrue(state.sessionEnded())
        assertTrue(state.sessionActive())
        assertTrue(state.requestStop("test"))
        assertTrue(state.finishStop())

        assertEquals(CrvControllerPhase.STOPPED, state.snapshot().phase)
        assertFalse(state.snapshot().sessionActive)
        assertEquals("airplay-active", transitions.first { it.current.sessionActive }.reason)
    }

    @Test fun wirelessLifecycleMaySkipNcm() {
        val state = CrvCarPlayStateMachine()
        state.start()
        state.usbMuxReady()
        state.lockdownReady()
        state.iap2Ready()
        state.networkReady()
        state.authenticated()
        state.sessionControlStarted()
        assertEquals(CrvControllerPhase.SESSION_CONTROL, state.snapshot().phase)
    }

    @Test fun sessionEndBeforeActiveRequestsHandshakeRecovery() {
        val state = CrvCarPlayStateMachine()
        state.start()
        state.usbMuxReady()
        state.lockdownReady()
        state.iap2Ready()
        state.networkReady()
        state.authenticated()
        state.sessionControlStarted()

        assertEquals(
            CrvSessionEndDisposition.HANDSHAKE_ENDED_BEFORE_ACTIVE,
            state.classifySessionEnd(),
        )
        assertFalse(state.snapshot().sessionActive)

        state.requestStop("test")
        assertEquals(CrvSessionEndDisposition.IGNORED, state.classifySessionEnd())
    }

    @Test fun illegalStageSkipIsRejected() {
        val state = CrvCarPlayStateMachine()
        state.start()
        val error = runCatching { state.lockdownReady() }.exceptionOrNull()
        assertTrue(error is IllegalStateException)
        assertEquals(CrvControllerPhase.STARTING, state.snapshot().phase)
    }

    @Test fun failureAndStopAreIdempotent() {
        val state = CrvCarPlayStateMachine()
        state.start()
        assertTrue(state.fail("boom"))
        assertFalse(state.fail("again"))
        assertTrue(state.requestStop("cleanup"))
        assertFalse(state.requestStop("again"))
        assertTrue(state.finishStop())
        assertFalse(state.finishStop())
    }
}
