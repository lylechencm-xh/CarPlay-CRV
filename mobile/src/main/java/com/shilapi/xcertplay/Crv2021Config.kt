package com.shilapi.xcertplay

object Crv2021Config {
    const val CARPLAY_WIDTH = 1280
    const val CARPLAY_HEIGHT = 720
    // Bound the negotiated stream rate on the legacy head unit; retain 1280x720 touch geometry.
    const val CARPLAY_MAX_FPS = 20
    const val USB_READ_TIMEOUT_MS = 1000
    const val USB_WRITE_TIMEOUT_MS = 1000
    const val WIRED_ONLY = false
    const val APPLE_VENDOR_ID = 0x05ac
}
