package com.shilapi.xcertplay

import org.junit.Assert.*
import org.junit.Test

class CrvTouchMoveFilterTest {
    private val target = Any()
    private fun frame(x: Double = 0.5, move: Boolean = true) =
        CrvTouchFrame(target, x, 0.5, true, 0.7, 0.7, false, move)

    @Test fun unchangedPixelMovesAvoidRepeatedSocketWrites() {
        val filter = CrvTouchMoveFilter<Any>(800, 480)
        val down = frame(move = false)
        assertTrue(filter.shouldSend(down))
        filter.completed(down, true)
        repeat(100) { assertFalse(filter.shouldSend(frame(0.5001))) }
        // The next reported pixel must still be delivered.
        assertTrue(filter.shouldSend(frame(0.501)))
    }

    @Test fun bothFingerCoordinatesAndContactChangesAreDelivered() {
        val filter = CrvTouchMoveFilter<Any>(800, 480)
        val down = frame().copy(down1 = true)
        assertTrue(filter.shouldSend(down))
        filter.completed(down, true)
        assertTrue(filter.shouldSend(down.copy(y1 = 0.71)))
        assertTrue(filter.shouldSend(down.copy(down1 = false)))
        assertTrue(filter.shouldSend(down.copy(y0 = 0.51)))
    }

    @Test fun edgesAlwaysSendIncludingSamePositionCancelAndNewPress() {
        val filter = CrvTouchMoveFilter<Any>(800, 480)
        val edge = frame(move = false)
        assertTrue(filter.shouldSend(edge))
        filter.completed(edge, true)
        assertTrue(filter.shouldSend(edge))
        val cancel = edge.copy(down0 = false)
        assertTrue(filter.shouldSend(cancel))
        filter.completed(cancel, true)
        assertTrue(filter.shouldSend(cancel))
        assertTrue(filter.shouldSend(edge))
    }

    @Test fun failedDeliveryDoesNotSuppressRetryOrReturnToPreviousPosition() {
        val filter = CrvTouchMoveFilter<Any>(800, 480)
        val first = frame()
        assertTrue(filter.shouldSend(first))
        filter.completed(first, true)
        val moved = frame(0.6)
        assertTrue(filter.shouldSend(moved))
        filter.completed(moved, false)
        assertTrue(filter.shouldSend(first))
        assertTrue(filter.shouldSend(moved))
    }

    @Test fun replacementSessionGetsItsFirstMoveEvenAtSameCoordinates() {
        val filter = CrvTouchMoveFilter<Any>(800, 480)
        val first = frame()
        assertTrue(filter.shouldSend(first))
        filter.completed(first, true)
        assertTrue(filter.shouldSend(first.copy(target = Any())))
    }
}
