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

        assertNull(recovery.breakBlockingControl(CrvRecoveryTrigger.TCP_EOF))
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
        assertEquals(CrvRecoveryTrigger.TCP_EOF, recovery.trigger)
    }

    @Test fun teardownStillNotifiesWhenIndividualCleanupFails() {
        val calls = mutableListOf<String>()
        val recovery = CrvControllerRecovery(
            closeControl = { throw IllegalStateException("already closed") },
            releaseTransport = { throw IllegalStateException("detach failed") },
            notifyStopped = { calls += "schedule-reconnect" },
        )

        assertNotNull(recovery.breakBlockingControl(CrvRecoveryTrigger.ACTIVE_DISCONNECT))
        recovery.completeWorker(
            closeUsbSession = { throw IllegalStateException("USB gone") },
            finishLifecycle = { calls += "finish-lifecycle" },
        )
        assertEquals(listOf("finish-lifecycle", "schedule-reconnect"), calls)
    }

    /**
     * A system VPN revocation closes the AirPlay listener first, which ends every session, and only
     * then reports the revocation. The service disconnect and a TCP close can race the same teardown.
     * Only the first cause may break the blocking control loop, and the recorded reason stays stable.
     */
    @Test fun systemRevocationTcpCloseAndServiceDisconnectShareOneCleanupPass() {
        val calls = mutableListOf<String>()
        val recovery = CrvControllerRecovery(
            closeControl = { calls += "close-control" },
            releaseTransport = { calls += "release-transport" },
            notifyStopped = { calls += "schedule-reconnect" },
        )

        assertNull(recovery.breakBlockingControl(CrvRecoveryTrigger.SYSTEM_VPN_REVOKED))
        assertNull(recovery.breakBlockingControl(CrvRecoveryTrigger.TCP_EOF))
        assertNull(recovery.breakBlockingControl(CrvRecoveryTrigger.VPN_SERVICE_DISCONNECTED))

        assertEquals(listOf("close-control"), calls)
        assertEquals(CrvRecoveryTrigger.SYSTEM_VPN_REVOKED, recovery.trigger)
    }

    @Test fun everyRecoveryTriggerBreaksTheControlLoopExactlyOnce() {
        for (trigger in CrvRecoveryTrigger.values()) {
            val calls = mutableListOf<String>()
            val recovery = CrvControllerRecovery(
                closeControl = { calls += "close-control" },
                releaseTransport = { calls += "release-transport" },
                notifyStopped = { calls += "schedule-reconnect" },
            )

            assertNull("$trigger must break the blocking loop", recovery.breakBlockingControl(trigger))
            assertNull("$trigger must not break the loop twice", recovery.breakBlockingControl(trigger))
            assertEquals("$trigger must close control once", listOf("close-control"), calls)
            assertEquals(trigger, recovery.trigger)
        }
    }

    @Test fun repeatedWorkerCompletionRunsTeardownOnce() {
        val calls = mutableListOf<String>()
        val recovery = CrvControllerRecovery(
            closeControl = { calls += "close-control" },
            releaseTransport = { calls += "release-transport" },
            notifyStopped = { calls += "schedule-reconnect" },
        )

        recovery.breakBlockingControl(CrvRecoveryTrigger.USB_DETACHED)
        val closeUsbSession = { calls += "close-usb" }
        recovery.completeWorker(closeUsbSession = closeUsbSession, finishLifecycle = {})
        recovery.completeWorker(closeUsbSession = closeUsbSession, finishLifecycle = {})

        assertEquals(
            listOf("close-control", "release-transport", "close-usb", "schedule-reconnect"),
            calls,
        )
    }

    @Test fun transportFailuresMapToDistinctRecoveryReasons() {
        assertEquals(
            CrvRecoveryTrigger.SYSTEM_VPN_REVOKED,
            recoveryTriggerForTransport("CarPlay VPN permission revoked"),
        )
        assertEquals(
            CrvRecoveryTrigger.SYSTEM_VPN_REVOKED,
            recoveryTriggerForTransport("carplay vpn permission revoked"),
        )
        assertEquals(
            CrvRecoveryTrigger.TCP_EOF,
            recoveryTriggerForTransport("airplay control read failed: unexpected EOF"),
        )
        assertEquals(
            CrvRecoveryTrigger.TRANSPORT_ERROR,
            recoveryTriggerForTransport("airplay event accept failed: SocketException"),
        )
    }
}
