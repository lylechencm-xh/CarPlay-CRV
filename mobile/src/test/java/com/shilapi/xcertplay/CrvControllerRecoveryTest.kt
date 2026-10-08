package com.shilapi.xcertplay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class CrvControllerRecoveryTest {
    @Test fun setupEofBreaksControlThenCompletesReconnectHandoffInOrder() {
        val calls = mutableListOf<String>()
        val recovery = CrvControllerRecovery(
            closeControl = { calls += "close-control" },
            releaseTransport = { calls += "release-transport" },
            notifyStopped = { calls += "schedule-reconnect" },
        )

        assertNull(recovery.breakBlockingControl())
        recovery.completeWorker(
            closeUsbSession = { calls += "close-usb" },
            finishLifecycle = { calls += "finish-lifecycle" },
        )

        assertEquals(
            listOf(
                "close-control",
                "release-transport",
                "close-usb",
                "finish-lifecycle",
                "schedule-reconnect",
            ),
            calls,
        )
    }

    @Test fun teardownStillNotifiesWhenIndividualCleanupFails() {
        val calls = mutableListOf<String>()
        val recovery = CrvControllerRecovery(
            closeControl = { throw IllegalStateException("already closed") },
            releaseTransport = { throw IllegalStateException("detach failed") },
            notifyStopped = { calls += "schedule-reconnect" },
        )

        assertNotNull(recovery.breakBlockingControl())
        recovery.completeWorker(
            closeUsbSession = { throw IllegalStateException("USB gone") },
            finishLifecycle = { calls += "finish-lifecycle" },
        )
        assertEquals(listOf("finish-lifecycle", "schedule-reconnect"), calls)
    }
}
