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
