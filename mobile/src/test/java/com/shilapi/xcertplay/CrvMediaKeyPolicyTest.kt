package com.shilapi.xcertplay

import org.junit.Assert.*
import org.junit.Test

class CrvMediaKeyPolicyTest {
    @Test fun duplicateDeliveryAndLongPressDoNotSkipMultipleSongs() {
        val policy = CrvMediaKeyPolicy()
        assertTrue(policy.accept(1, 87, 100, 0))
        assertFalse(policy.accept(1, 87, 100, 0))
        assertFalse(policy.accept(1, 87, 100, 1))
        assertTrue(policy.accept(1, 87, 101, 0))
        assertTrue(policy.accept(1, 88, 102, 0))
        assertFalse(policy.accept(1, 87, 100, 0))
    }

    @Test fun separateDevicesAreNotMistakenForDuplicates() {
        val policy = CrvMediaKeyPolicy()
        assertTrue(policy.accept(1, 87, 100, 0))
        assertTrue(policy.accept(2, 87, 100, 0))
    }
}
