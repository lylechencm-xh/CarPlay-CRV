package com.shilapi.xcertplay.network

import java.io.IOException
import org.junit.Assert.*
import org.junit.Test

class TunPendingPacketWriterTest {
    @Test fun transientCongestionKeepsThePacketAndUsesBoundedBackoff() {
        var now = 0L
        val pauses = mutableListOf<Long>()
        var attempts = 0
        val writer = TunPendingPacketWriter({ true }, { it.message == "EAGAIN" }, { now }, {
            pauses += it; now += it
        })
        assertTrue(writer.write { if (++attempts <= 7) throw IOException("EAGAIN") })
        assertEquals(listOf(1L, 2L, 4L, 8L, 16L, 20L, 20L).map { it * 1_000_000 }, pauses)
        assertEquals(8, attempts)
    }

    @Test fun permanentErrorIsNotRetriedOrHidden() {
        val failure = IOException("bad descriptor")
        val writer = TunPendingPacketWriter({ true }, { false })
        assertSame(failure, runCatching { writer.write { throw failure } }.exceptionOrNull())
    }

    @Test fun persistentCongestionFailsAndClosingStopsRetry() {
        var now = 0L
        val writer = TunPendingPacketWriter({ true }, { true }, { now }, { now += 2_500_000_000L })
        assertTrue(runCatching { writer.write { throw IOException("EAGAIN") } }.exceptionOrNull() is IOException)
        var running = true
        val closing = TunPendingPacketWriter({ running }, { true }, pause = { running = false })
        assertFalse(closing.write { throw IOException("EAGAIN") })
    }
}
