package com.shilapi.xcertplay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CrvTouchSlotStateTest {
    @Test fun promotedThirdFingerIsReportedAsAnEdgeRatherThanMotion() {
        val state = CrvTouchSlotState()
        state.press(1, 0.1, 0.1)
        state.press(2, 0.2, 0.2)
        assertFalse(state.updateContact(3, 0.3, 0.3))
        assertFalse(state.updateContact(2, 0.25, 0.25))
        state.lift(1, 0.1, 0.1)
        state.release(1)
        assertTrue(state.updateContact(3, 0.4, 0.4))
        assertEquals(0, state.slotOf(3))
        assertEquals(1, state.slotOf(2))
        assertTrue(state.isDown(0))
        assertFalse(state.updateContact(3, 0.5, 0.5))
        assertEquals(0.5, state.x(0), 0.0)
    }
    @Test
    fun pointerKeepsItsSlotWhenOtherFingerLifts() {
        val state = CrvTouchSlotState()
        assertEquals(0, state.press(10, 0.1, 0.2))
        assertEquals(1, state.press(20, 0.8, 0.7))

        state.lift(10, 0.2, 0.3)
        assertFalse(state.isDown(0))
        assertTrue(state.isDown(1))
        state.release(10)

        assertEquals(1, state.slotOf(20))
        assertEquals(0, state.press(30, 0.4, 0.5))
        assertEquals(1, state.slotOf(20))
    }

    @Test
    fun cancelReleasesBothSlotsEvenWithoutPointerLookup() {
        val state = CrvTouchSlotState()
        state.press(1, 0.1, 0.1)
        state.press(2, 0.9, 0.9)

        state.cancelAll()

        assertFalse(state.isDown(0))
        assertFalse(state.isDown(1))
    }

    @Test
    fun thirdPointerIsIgnoredUntilASlotIsFree() {
        val state = CrvTouchSlotState()
        state.press(1, 0.1, 0.1)
        state.press(2, 0.2, 0.2)
        assertEquals(-1, state.press(3, 0.3, 0.3))

        state.lift(1, 0.1, 0.1)
        state.release(1)

        assertEquals(0, state.press(3, 0.3, 0.3))
    }
}
