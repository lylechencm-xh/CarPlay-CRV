package com.shilapi.xcertplay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CrvProtocolTraceTest {
    @Test fun emitsStableApi17SafeFields() {
        var now = 1_000L
        val lines = mutableListOf<String>()
        val trace = CrvProtocolTraceRecorder(lines::add) { now }

        now = 1_025L
        trace.signal(
            CrvProtocolLayer.NCM,
            channel = "link state",
            byteCount = 64,
            detail = "backend=userspace",
        )

        assertEquals(1, lines.size)
        assertEquals(
            listOf(
                "CRVTRACE", "1", "0", "25", "NCM", "SIGNAL",
                "link%20state", "64", "backend%3Duserspace",
            ),
            lines.single().split('\t'),
        )
    }

    @Test fun sensitiveDetailsAreRedactedBeforeEmission() {
        val lines = mutableListOf<String>()
        val trace = CrvProtocolTraceRecorder(lines::add) { 1L }

        trace.record(
            CrvProtocolLayer.MFI,
            CrvProtocolDirection.DEVICE_TO_HOST,
            channel = "authenticate",
            byteCount = 64,
            detail = "signature=secret-material",
        )

        val line = lines.single()
        assertTrue(line.endsWith("%5Bredacted%5D"))
        assertFalse(line.contains("secret-material"))

        trace.fault(CrvProtocolLayer.CARPLAY_SESSION, "transport", "peer=AA:BB:CC:DD:EE:FF")
        assertTrue(lines.last().endsWith("%5Bredacted%5D"))
    }

    @Test fun emitterFailureNeverStopsTheConnectionPath() {
        val trace = CrvProtocolTraceRecorder({ throw IllegalStateException("disk full") }) { 1L }
        trace.signal(CrvProtocolLayer.USB, "attached")
    }
}
