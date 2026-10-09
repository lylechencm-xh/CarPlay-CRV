package com.shilapi.xcertplay.network

import com.shilapi.xcertplay.transport.NcmWriteResult
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class NcmPendingFrameSenderTest {
    @Test fun retriesTheSameFrameUntilUsbAcceptsIt() {
        val frame = byteArrayOf(1, 2, 3)
        val attempts = mutableListOf<ByteArray>()
        var now = 0L
        val sender = NcmPendingFrameSender(
            transmit = { candidate ->
                attempts += candidate
                if (attempts.size < 3) NcmWriteResult.NOT_READY else NcmWriteResult.SENT
            },
            isRunning = { true },
            clockNanos = { now },
            pause = { now += 100_000_000L },
        )

        assertTrue(sender.send(frame))
        assertEquals(3, attempts.size)
        attempts.forEach { assertSame(frame, it) }
    }

    @Test fun reportsAStalledBulkOutInsteadOfDiscardingTheFrame() {
        var now = 0L
        val sender = NcmPendingFrameSender(
            transmit = { NcmWriteResult.NOT_READY },
            isRunning = { true },
            clockNanos = { now },
            pause = { now += 45_000_000_000L },
        )

        val error = runCatching { sender.send(byteArrayOf(7)) }.exceptionOrNull()
        assertTrue(error is IOException)
        assertTrue(error!!.message.orEmpty().contains("90 seconds"))
    }

    @Test fun stopsRetryingWhenTheBridgeCloses() {
        var running = true
        val sender = NcmPendingFrameSender(
            transmit = { NcmWriteResult.NOT_READY },
            isRunning = { running },
            pause = { running = false },
        )

        assertFalse(sender.send(byteArrayOf(9)))
    }
}
