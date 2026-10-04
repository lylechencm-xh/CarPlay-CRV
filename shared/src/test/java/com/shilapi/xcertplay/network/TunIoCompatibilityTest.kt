package com.shilapi.xcertplay.network

import java.io.IOException
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TunIoCompatibilityTest {
    @Test
    fun recognizesKitKatWouldBlockMessages() {
        assertTrue(TunIoCompatibility.isWouldBlock(IOException("read failed: EAGAIN (Try again)")))
        assertTrue(TunIoCompatibility.isWouldBlock(IOException("EWOULDBLOCK")))
        assertTrue(TunIoCompatibility.isWouldBlock(IOException("Resource temporarily unavailable")))
    }

    @Test
    fun recognizesWouldBlockInCauseChain() {
        assertTrue(
            TunIoCompatibility.isWouldBlock(
                IOException("read failed", RuntimeException("EAGAIN")),
            ),
        )
    }

    @Test
    fun ordinaryTransportErrorsAreNotRetried() {
        assertFalse(TunIoCompatibility.isWouldBlock(IOException("bad file descriptor")))
        assertFalse(TunIoCompatibility.isWouldBlock(IOException("connection reset")))
    }
}
