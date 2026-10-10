package com.shilapi.xcertplay.network

import org.junit.Assert.*
import org.junit.Test

class NcmTransferStatsTest {
    @Test fun reportsFailuresSeparatelyAndResetsEachWindow() {
        var now = 0L
        val output = mutableListOf<String>()
        val stats = NcmTransferStats("ncm-to-tun", output::add) { now }
        stats.transferred(100, 2000)
        stats.transferred(0, 9000, success = false)
        assertTrue(output.isEmpty())
        now = 5_000_000_000L
        stats.transferred(50, 3000)
        assertTrue(output.single().contains("packets=2 bytes=150 failures=1 writeMaxUs=9"))
        stats.flush(ended = true)
        assertTrue(output.last().contains("packets=0 bytes=0 failures=0 writeMaxUs=0"))
        assertTrue(output.last().contains("ended=true"))
    }
}
