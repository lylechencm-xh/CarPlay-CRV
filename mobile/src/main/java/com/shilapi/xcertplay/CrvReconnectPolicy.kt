package com.shilapi.xcertplay

import java.util.Locale
import java.util.concurrent.atomic.AtomicLong

internal enum class CrvReconnectFailure {
    TRANSIENT, PHONE_LOCKED, TRUST_REQUIRED, USB_MISSING, PERMISSION_REQUIRED,
}

internal fun classifyCrvReconnectFailure(error: Throwable): CrvReconnectFailure {
    var current: Throwable? = error
    repeat(8) {
        val cause = current ?: return CrvReconnectFailure.TRANSIENT
        val text = (cause.javaClass.simpleName + " " + cause.message.orEmpty()).lowercase(Locale.US)
        when {
            "passwordprotected" in text || "password protected" in text ->
                return CrvReconnectFailure.PHONE_LOCKED
            "userdeniedpairing" in text || "denied lockdown pairing" in text || "lockdown pairing timed out" in text ->
                return CrvReconnectFailure.TRUST_REQUIRED
            "usb detached" in text -> return CrvReconnectFailure.USB_MISSING
            "permission" in text || "vpn revoked" in text || "vpnservice.establish returned null" in text ->
                return CrvReconnectFailure.PERMISSION_REQUIRED
        }
        current = cause.cause.takeIf { it !== cause }
    }
    return CrvReconnectFailure.TRANSIENT
}

internal data class CrvReconnectDecision(
    val delayMillis: Long?,
    val consumesAttempt: Boolean,
    val message: String,
)

internal fun crvReconnectDecision(failure: CrvReconnectFailure, attempts: Int): CrvReconnectDecision =
    when (failure) {
        CrvReconnectFailure.PHONE_LOCKED ->
            CrvReconnectDecision(15_000L, false, "Unlock iPhone; checking again in 15 seconds")
        CrvReconnectFailure.TRUST_REQUIRED ->
            CrvReconnectDecision(null, false, "Allow Trust on iPhone, then tap Connect")
        CrvReconnectFailure.USB_MISSING ->
            CrvReconnectDecision(null, false, "Waiting for iPhone USB connection")
        CrvReconnectFailure.PERMISSION_REQUIRED ->
            CrvReconnectDecision(null, false, "Allow USB and CarPlay network permissions, then tap Connect")
        CrvReconnectFailure.TRANSIENT -> {
            val delays = longArrayOf(1_000L, 2_000L, 4_000L, 8_000L)
            if (attempts >= delays.size) {
                CrvReconnectDecision(null, false, "CarPlay stopped; tap Connect to retry")
            } else {
                CrvReconnectDecision(delays[attempts.coerceAtLeast(0)], true,
                    "CarPlay stopped; retrying session ${attempts + 1}/${delays.size}")
            }
        }
    }

/** Also checked after dispatch to the UI, where callbacks from a retired worker may arrive late. */
internal class CrvConnectionEpoch {
    private val value = AtomicLong()
    fun advance(): Long = value.incrementAndGet()
    fun isCurrent(epoch: Long): Boolean = epoch == value.get()
}

internal class CrvSessionStability {
    private var epoch: Long? = null
    private var activeSince: Long? = null

    fun active(epoch: Long, nowMillis: Long) {
        if (this.epoch == epoch && activeSince != null) return
        this.epoch = epoch
        activeSince = nowMillis
    }

    fun clear() { epoch = null; activeSince = null }

    fun isActive(epoch: Long): Boolean = this.epoch == epoch && activeSince != null

    fun isStable(epoch: Long, nowMillis: Long): Boolean =
        isActive(epoch) && nowMillis - activeSince!! >= STABLE_MILLIS

    companion object { const val STABLE_MILLIS = 60_000L }
}

internal fun crvRecoveryCopy(message: String): Pair<String, String>? = when {
    message.startsWith("Unlock iPhone") ->
        "Unlock your iPhone" to "Keep USB connected. CarPlay will check again in 15 seconds."
    message.startsWith("Allow Trust") ->
        "Allow Trust on iPhone" to "Unlock your iPhone, approve Trust if prompted, then tap Connect."
    message.startsWith("Waiting for iPhone USB connection") ->
        "Waiting for iPhone" to "Connect your iPhone to the USB data port."
    message.startsWith("Allow USB and CarPlay network permissions") ->
        "Allow connection permissions" to "Tap Connect and approve the USB or network permission request."
    message.startsWith("Finishing previous connection") ->
        "Finishing previous connection" to "CarPlay will continue once the previous connection is closed."
    message.startsWith("CarPlay stopped; tap Connect") ->
        "Connection stopped" to "Check your iPhone and the cable, then tap Connect to try again."
    else -> null
}

/** UI-thread gate: a newer user action replaces a deferred action while cleanup is pending. */
internal class CrvCleanupGate {
    var pending: Int = 0
        private set
    private var deferred: (() -> Unit)? = null
    fun begin() { pending++ }
    fun clearDeferred() { deferred = null }
    fun defer(action: () -> Unit): Boolean {
        if (pending == 0) return false
        deferred = action
        return true
    }
    fun complete(): (() -> Unit)? {
        check(pending > 0)
        pending--
        if (pending != 0) return null
        return deferred.also { deferred = null }
    }
}

/** Counts actual starts across every automatic entry point, including USB reenumeration. */
internal class CrvConnectionAttemptBudget(private val limit: Int = 5) {
    var attempts = 0; private set
    fun tryStart(): Boolean {
        if (attempts >= limit) return false
        attempts++
        return true
    }
    fun reset() { attempts = 0 }
}
