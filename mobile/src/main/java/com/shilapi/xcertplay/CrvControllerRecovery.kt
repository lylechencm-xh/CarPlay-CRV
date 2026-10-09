package com.shilapi.xcertplay

import java.util.concurrent.atomic.AtomicBoolean

/**
 * Testable recovery seam around the blocking iAP2 control loop.
 *
 * Closing control is what makes the worker return. Once it does, teardown order remains transport,
 * USB session, lifecycle, then the activity callback that schedules a reconnect.
 */
internal enum class CrvRecoveryTrigger {
    ACTIVE_DISCONNECT,
    VPN_SERVICE_DISCONNECTED,
    SYSTEM_VPN_REVOKED,
    TCP_EOF,
    USB_DETACHED,
    TRANSPORT_ERROR,
}

internal fun recoveryTriggerForTransport(message: String): CrvRecoveryTrigger = when {
    message.contains("VPN permission revoked", ignoreCase = true) ->
        CrvRecoveryTrigger.SYSTEM_VPN_REVOKED
    message.contains("EOF", ignoreCase = true) -> CrvRecoveryTrigger.TCP_EOF
    else -> CrvRecoveryTrigger.TRANSPORT_ERROR
}

internal class CrvControllerRecovery(
    private val closeControl: () -> Unit,
    private val releaseTransport: () -> Unit,
    private val notifyStopped: () -> Unit,
) {
    private val controlBreakRequested = AtomicBoolean(false)
    private val workerCompleted = AtomicBoolean(false)
    @Volatile var trigger: CrvRecoveryTrigger? = null
        private set

    fun breakBlockingControl(reason: CrvRecoveryTrigger): Throwable? {
        if (!controlBreakRequested.compareAndSet(false, true)) return null
        trigger = reason
        return runCatching(closeControl).exceptionOrNull()
    }

    fun completeWorker(
        closeUsbSession: () -> Unit,
        finishLifecycle: () -> Unit,
    ) {
        if (!workerCompleted.compareAndSet(false, true)) return
        runCatching(releaseTransport)
        runCatching(closeUsbSession)
        runCatching(finishLifecycle)
        runCatching(notifyStopped)
    }
}
