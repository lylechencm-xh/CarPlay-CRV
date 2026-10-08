package com.shilapi.xcertplay

/** Ordered controller milestones. AirPlay session activity is tracked separately. */
internal enum class CrvControllerPhase {
    NEW,
    STARTING,
    NCM_READY,
    USBMUX_READY,
    LOCKDOWN_READY,
    IAP2_READY,
    NETWORK_READY,
    AUTHENTICATED,
    SESSION_CONTROL,
    FAILED,
    STOPPING,
    STOPPED,
}

internal data class CrvControllerSnapshot(
    val phase: CrvControllerPhase,
    val sessionActive: Boolean,
)

internal data class CrvControllerTransition(
    val previous: CrvControllerSnapshot,
    val current: CrvControllerSnapshot,
    val reason: String,
)

internal enum class CrvSessionEndDisposition {
    ACTIVE_SESSION_ENDED,
    HANDSHAKE_ENDED_BEFORE_ACTIVE,
    IGNORED,
}

/**
 * Thread-safe lifecycle for the CR-V controller.
 *
 * The wired path includes [CrvControllerPhase.NCM_READY]; Wi-Fi handoff intentionally skips it.
 * All other bring-up phases are ordered, while AirPlay may become active or end asynchronously
 * after the network listener is ready.
 */
internal class CrvCarPlayStateMachine(
    private val onTransition: (CrvControllerTransition) -> Unit = {},
) {
    private var snapshot = CrvControllerSnapshot(CrvControllerPhase.NEW, sessionActive = false)

    @Synchronized
    fun snapshot(): CrvControllerSnapshot = snapshot

    fun start() = advance(CrvControllerPhase.STARTING, "start")
    fun ncmReady() = advance(CrvControllerPhase.NCM_READY, "ncm-ready")
    fun usbMuxReady() = advance(CrvControllerPhase.USBMUX_READY, "usbmux-ready")
    fun lockdownReady() = advance(CrvControllerPhase.LOCKDOWN_READY, "lockdown-ready")
    fun iap2Ready() = advance(CrvControllerPhase.IAP2_READY, "iap2-ready")
    fun networkReady() = advance(CrvControllerPhase.NETWORK_READY, "network-ready")
    fun authenticated() = advance(CrvControllerPhase.AUTHENTICATED, "authenticated")
    fun sessionControlStarted() = advance(CrvControllerPhase.SESSION_CONTROL, "session-control")

    @Synchronized
    fun sessionActive(): Boolean {
        if (snapshot.phase !in SESSION_PHASES || snapshot.sessionActive) return false
        return update(snapshot.copy(sessionActive = true), "airplay-active")
    }

    @Synchronized
    fun classifySessionEnd(): CrvSessionEndDisposition {
        if (snapshot.sessionActive) {
            update(snapshot.copy(sessionActive = false), "airplay-ended")
            return CrvSessionEndDisposition.ACTIVE_SESSION_ENDED
        }
        return if (snapshot.phase == CrvControllerPhase.SESSION_CONTROL || snapshot.phase == CrvControllerPhase.AUTHENTICATED) {
            CrvSessionEndDisposition.HANDSHAKE_ENDED_BEFORE_ACTIVE
        } else {
            CrvSessionEndDisposition.IGNORED
        }
    }

    @Synchronized
    fun sessionEnded(): Boolean {
        return classifySessionEnd() == CrvSessionEndDisposition.ACTIVE_SESSION_ENDED
    }

    @Synchronized
    fun fail(reason: String): Boolean {
        if (snapshot.phase in TERMINAL_OR_STOPPING) return false
        return update(
            CrvControllerSnapshot(CrvControllerPhase.FAILED, sessionActive = false),
            reason,
        )
    }

    @Synchronized
    fun requestStop(reason: String): Boolean {
        if (snapshot.phase == CrvControllerPhase.STOPPED || snapshot.phase == CrvControllerPhase.STOPPING) {
            return false
        }
        return update(
            CrvControllerSnapshot(CrvControllerPhase.STOPPING, sessionActive = false),
            reason,
        )
    }

    @Synchronized
    fun finishStop(): Boolean {
        if (snapshot.phase == CrvControllerPhase.STOPPED) return false
        check(snapshot.phase == CrvControllerPhase.STOPPING) {
            "Cannot finish controller stop from ${snapshot.phase}"
        }
        return update(
            CrvControllerSnapshot(CrvControllerPhase.STOPPED, sessionActive = false),
            "stopped",
        )
    }

    @Synchronized
    fun isStopping(): Boolean = snapshot.phase in TERMINAL_OR_STOPPING

    @Synchronized
    private fun advance(target: CrvControllerPhase, reason: String): Boolean {
        val allowed = ALLOWED_PHASES[snapshot.phase].orEmpty()
        check(target in allowed) {
            "Illegal CR-V controller transition ${snapshot.phase} -> $target"
        }
        return update(snapshot.copy(phase = target), reason)
    }

    private fun update(next: CrvControllerSnapshot, reason: String): Boolean {
        val previous = snapshot
        if (previous == next) return false
        snapshot = next
        onTransition(CrvControllerTransition(previous, next, reason))
        return true
    }

    private companion object {
        val SESSION_PHASES = setOf(CrvControllerPhase.AUTHENTICATED)
        val TERMINAL_OR_STOPPING = setOf(
            CrvControllerPhase.FAILED,
            CrvControllerPhase.STOPPING,
            CrvControllerPhase.STOPPED,
        )
        val ALLOWED_PHASES = mapOf(
            CrvControllerPhase.NEW to setOf(CrvControllerPhase.STARTING),
            CrvControllerPhase.STARTING to setOf(
                CrvControllerPhase.NCM_READY,
                CrvControllerPhase.USBMUX_READY,
            ),
            CrvControllerPhase.NCM_READY to setOf(CrvControllerPhase.USBMUX_READY),
            CrvControllerPhase.USBMUX_READY to setOf(CrvControllerPhase.LOCKDOWN_READY),
            CrvControllerPhase.LOCKDOWN_READY to setOf(CrvControllerPhase.IAP2_READY),
            CrvControllerPhase.IAP2_READY to setOf(CrvControllerPhase.NETWORK_READY),
            CrvControllerPhase.NETWORK_READY to setOf(CrvControllerPhase.SESSION_CONTROL),
            CrvControllerPhase.SESSION_CONTROL to setOf(CrvControllerPhase.AUTHENTICATED),
        )
    }
}
