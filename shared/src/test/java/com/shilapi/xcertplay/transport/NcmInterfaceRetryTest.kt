package com.shilapi.xcertplay.transport

import org.junit.Assert.*
import org.junit.Test

class NcmInterfaceRetryTest {
    @Test fun temporaryRefusalRetriesUntilSuccess() {
        var attempts = 0
        val waits = mutableListOf<Long>()
        assertTrue(NcmInterfaceRetry.run(sleep = { waits += it }) { ++attempts == 3 })
        assertEquals(3, attempts)
        assertEquals(listOf(100L, 100L), waits)
    }

    @Test fun persistentRefusalStopsWithoutDelayAfterLastAttempt() {
        var attempts = 0
        val waits = mutableListOf<Long>()
        assertFalse(NcmInterfaceRetry.run(sleep = { waits += it }) { attempts++; false })
        assertEquals(5, attempts)
        assertEquals(4, waits.size)
    }

    @Test fun preserveDriverClaimDoesNotRetry() {
        var attempts = 0
        assertFalse(NcmInterfaceRetry.run(attempts = 1, sleep = { fail("must not wait") }) {
            attempts++; false
        })
        assertEquals(1, attempts)
    }

    @Test fun interruptedRetryCancelsAndPreservesInterruptFlag() {
        var attempts = 0
        try {
            NcmInterfaceRetry.run(sleep = { throw InterruptedException("stop") }) {
                attempts++; false
            }
            fail("Expected cancellation")
        } catch (_: InterruptedException) {
            assertTrue(Thread.currentThread().isInterrupted)
            assertEquals(1, attempts)
        } finally {
            Thread.interrupted()
        }
    }
}
