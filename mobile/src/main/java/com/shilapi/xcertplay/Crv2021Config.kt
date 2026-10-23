package com.shilapi.xcertplay

object Crv2021Config {
    const val CARPLAY_WIDTH = 1280
    const val CARPLAY_HEIGHT = 720
    // Leave CPU headroom for music and LaneWatch on the legacy head unit. This is a sender
    // negotiation limit, not compressed-frame dropping; keep the original touch geometry.
    const val CARPLAY_MAX_FPS = 15
    const val USB_READ_TIMEOUT_MS = 1000
    const val USB_WRITE_TIMEOUT_MS = 1000
    const val WIRED_ONLY = false
    const val APPLE_VENDOR_ID = 0x05ac
}
