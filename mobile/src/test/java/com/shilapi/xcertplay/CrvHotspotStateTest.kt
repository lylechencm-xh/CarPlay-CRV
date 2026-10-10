package com.shilapi.xcertplay

import org.junit.Assert.*
import org.junit.Test

class CrvHotspotStateTest {
    @Test fun onlyDisabledAllowsStartingAndFailuresAreNotReportedAsAnExistingHotspot() {
        assertNull(CrvHotspotState.startError(11))
        assertTrue(CrvHotspotState.startError(10)!!.contains("disabling"))
        assertTrue(CrvHotspotState.startError(12)!!.contains("enabling"))
        assertTrue(CrvHotspotState.startError(13)!!.contains("Turn off"))
        assertTrue(CrvHotspotState.startError(14)!!.contains("failed"))
        assertTrue(CrvHotspotState.startError(99)!!.contains("unsupported: 99"))
    }

    @Test fun falseAndMissingVendorResultsAreNotAcceptedAsSuccessfulRestoration() {
        CrvHotspotState.requireAccepted(true, "restore")
        for (result in listOf(false, null)) {
            try { CrvHotspotState.requireAccepted(result, "restore"); fail("Must reject $result") }
            catch (error: IllegalStateException) { assertTrue(error.message!!.contains("restore")) }
        }
    }
}
