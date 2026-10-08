package com.shilapi.xcertplay

/**
 * Testable recovery seam around the blocking iAP2 control loop.
 *
 * Closing control is what makes the worker return. Once it does, teardown order remains transport,
 * USB session, lifecycle, then the activity callback that schedules a reconnect.
 */
internal class CrvControllerRecovery(
    private val closeControl: () -> Unit,
    private val releaseTransport: () -> Unit,
    private val notifyStopped: () -> Unit,
) {
    fun breakBlockingControl(): Throwable? =
        runCatching(closeControl).exceptionOrNull()

    fun completeWorker(
        closeUsbSession: () -> Unit,
        finishLifecycle: () -> Unit,
    ) {
        runCatching(releaseTransport)
        runCatching(closeUsbSession)
        runCatching(finishLifecycle)
        runCatching(notifyStopped)
    }
}
