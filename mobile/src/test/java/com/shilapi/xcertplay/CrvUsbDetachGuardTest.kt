package com.shilapi.xcertplay

import org.junit.Assert.*
import org.junit.Test

class CrvUsbDetachGuardTest {
    private val phone = CrvUsbIdentity("/dev/bus/usb/001/005", 5, 0x05ac, 0x12a8)

    @Test fun delayedOrUnrelatedAppleDetachCannotCloseCurrentPhone() {
        val previous = phone.copy(name = "/dev/bus/usb/001/004", id = 4)
        assertFalse(shouldHandleCrvUsbDetach(previous, phone, listOf(phone)))
        assertFalse(shouldHandleCrvUsbDetach(phone, phone, listOf(phone)))
        assertFalse(shouldHandleCrvUsbDetach(phone, null, emptyList()))
    }

    @Test fun absentCurrentPhoneStillClosesAndInventoryFailureDoesNotHideDetach() {
        assertTrue(shouldHandleCrvUsbDetach(phone, phone, emptyList()))
        assertTrue(shouldHandleCrvUsbDetach(phone, phone, null))
        assertTrue(shouldHandleCrvUsbDetach(phone, phone, listOf(phone.copy(id = 6))))
    }
}
