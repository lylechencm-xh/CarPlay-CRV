package com.shilapi.xcertplay.transport

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NcmUsbPresenceGuardTest {
    @Test fun timeoutsDoNotMeanDetachWhenDeviceIsPresent() {
        val guard = NcmUsbPresenceGuard { true }
        for (n in 1..512) assertFalse(guard.disconnectedAfterFailures(n))
    }

    @Test fun requiresTwoMissingObservationsBeforeReportingDetach() {
        val guard = NcmUsbPresenceGuard { false }
        for (n in 1..127) assertFalse(guard.disconnectedAfterFailures(n))
        assertTrue(guard.disconnectedAfterFailures(128))
    }

    @Test fun aRecoveredDeviceOrSuccessfulTransferResetsTheGuard() {
        var attached = false
        val guard = NcmUsbPresenceGuard { attached }
        assertFalse(guard.disconnectedAfterFailures(64))
        attached = true
        assertFalse(guard.disconnectedAfterFailures(128))
        attached = false
        assertFalse(guard.disconnectedAfterFailures(192))
        guard.reset()
        assertFalse(guard.disconnectedAfterFailures(64))
    }

    @Test fun missingObserverOrObserverExceptionNeverSignalsDetach() {
        val absent = NcmUsbPresenceGuard(null)
        val throws = NcmUsbPresenceGuard { error("USB manager not available") }
        assertFalse(absent.disconnectedAfterFailures(128))
        assertFalse(throws.disconnectedAfterFailures(128))
    }
}
