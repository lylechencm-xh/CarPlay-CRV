package com.shilapi.xcertplay

/** Stable, low-cardinality stages used by the head-unit status display and field diagnostics. */
enum class CrvConnectionStage {
    IDLE,
    USB_DETECTED,
    USB_PERMISSION,
    USB_REENUMERATION,
    USBMUX_READY,
    NETWORK_READY,
    LOCKDOWN,
    IAP2,
    MFI,
    NCM,
    AIRPLAY_LISTENING,
    AIRPLAY_CONNECTED,
    WIFI_HOTSPOT,
    WIFI_HANDOFF,
    CARPLAY_ACTIVE,
    RETRYING,
    ERROR,
}

internal fun isCrvUiDiagnostic(message: String): Boolean =
    message.startsWith("Receive:", ignoreCase = true)

internal fun isCrvConnectionError(message: String): Boolean {
    val value = message.lowercase(java.util.Locale.US)
    // These describe an in-progress handshake or an expected retry transition. The field logs
    // include "timeoutMs" and "stopped" in them even when no failure has occurred yet.
    if (value.startsWith("iap2 awaiting ")) return false
    if (value.startsWith("controller state stopping -> stopped reason=stopped")) return false
    if (value.startsWith("honda cdc-ncm fallback blocked ") ||
        value.startsWith("honda cdc-ncm fallback escalating ")) return false
    if (value.startsWith("iap2 wired control ended terminal=timed_out")) return true
    if ("mfi identity missing" in value) return true
    return CRV_CONNECTION_ERROR_MARKERS.any(value::contains)
}

internal fun monotonicConnectionStage(
    current: CrvConnectionStage,
    candidate: CrvConnectionStage,
): CrvConnectionStage {
    if (candidate == CrvConnectionStage.ERROR || candidate == CrvConnectionStage.RETRYING) {
        return candidate
    }
    if (candidate == CrvConnectionStage.IDLE) return current
    return if (connectionStageRank(candidate) >= connectionStageRank(current)) candidate else current
}

private fun connectionStageRank(stage: CrvConnectionStage): Int = when (stage) {
    CrvConnectionStage.IDLE,
    CrvConnectionStage.USB_DETECTED,
    CrvConnectionStage.USB_PERMISSION,
    CrvConnectionStage.USB_REENUMERATION -> 0
    CrvConnectionStage.USBMUX_READY -> 1
    CrvConnectionStage.LOCKDOWN -> 2
    CrvConnectionStage.IAP2 -> 3
    CrvConnectionStage.MFI -> 4
    CrvConnectionStage.NCM,
    CrvConnectionStage.NETWORK_READY,
    CrvConnectionStage.WIFI_HOTSPOT,
    CrvConnectionStage.WIFI_HANDOFF -> 5
    CrvConnectionStage.AIRPLAY_LISTENING,
    CrvConnectionStage.AIRPLAY_CONNECTED -> 6
    CrvConnectionStage.CARPLAY_ACTIVE -> 7
    CrvConnectionStage.RETRYING,
    CrvConnectionStage.ERROR -> -1
}

private val CRV_CONNECTION_ERROR_MARKERS = listOf(
    "failed",
    "failure",
    "error",
    "denied",
    "timed out",
    "timeout",
    "stopped",
    "disconnected",
    "detached",
    "rejected",
    "could not",
)
