package com.shilapi.xcertplay

/**
 * Stable Android pointer-id to CarPlay HID slot mapping.
 *
 * MotionEvent pointer indices can be reordered when one finger lifts. CarPlay's two HID
 * collections are stable slots, so keep a pointer in the same slot until its up report is sent.
 */
internal class CrvTouchSlotState {
    private val pointerIds = intArrayOf(INVALID_POINTER, INVALID_POINTER)
    private val xs = DoubleArray(SLOT_COUNT)
    private val ys = DoubleArray(SLOT_COUNT)
    private val downs = BooleanArray(SLOT_COUNT)

    fun reset() {
        for (slot in 0 until SLOT_COUNT) {
            pointerIds[slot] = INVALID_POINTER
            xs[slot] = 0.0
            ys[slot] = 0.0
            downs[slot] = false
        }
    }

    fun press(pointerId: Int, x: Double, y: Double): Int {
        val existing = slotOf(pointerId)
        val slot = if (existing >= 0) existing else pointerIds.indexOf(INVALID_POINTER)
        if (slot < 0) return -1
        pointerIds[slot] = pointerId
        xs[slot] = x
        ys[slot] = y
        downs[slot] = true
        return slot
    }

    fun move(pointerId: Int, x: Double, y: Double): Boolean {
        val slot = slotOf(pointerId)
        if (slot < 0) return false
        xs[slot] = x
        ys[slot] = y
        return true
    }

    fun lift(pointerId: Int, x: Double, y: Double): Int {
        val slot = slotOf(pointerId)
        if (slot < 0) return -1
        xs[slot] = x
        ys[slot] = y
        downs[slot] = false
        return slot
    }

    fun cancelAll() {
        for (slot in 0 until SLOT_COUNT) downs[slot] = false
    }

    /** Call only after the up-state for [pointerId] has been transmitted. */
    fun release(pointerId: Int) {
        val slot = slotOf(pointerId)
        if (slot < 0) return
        pointerIds[slot] = INVALID_POINTER
        downs[slot] = false
    }

    fun slotOf(pointerId: Int): Int {
        for (slot in 0 until SLOT_COUNT) {
            if (pointerIds[slot] == pointerId) return slot
        }
        return -1
    }

    fun x(slot: Int): Double = xs[slot]
    fun y(slot: Int): Double = ys[slot]
    fun isDown(slot: Int): Boolean = downs[slot]

    private companion object {
        const val SLOT_COUNT = 2
        const val INVALID_POINTER = -1
    }
}
