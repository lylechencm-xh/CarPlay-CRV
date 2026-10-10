package com.shilapi.xcertplay

internal object CrvHotspotState {
    const val DISABLING = 10
    const val DISABLED = 11
    const val ENABLING = 12
    const val ENABLED = 13
    const val FAILED = 14

    fun startError(state: Int): String? = when (state) {
        DISABLED -> null
        DISABLING -> "Vehicle hotspot is still disabling; retry after it stops"
        ENABLING -> "Vehicle hotspot is already enabling; wait for it to finish"
        ENABLED -> "Turn off the existing vehicle hotspot before Wi-Fi handoff"
        FAILED -> "Honda Wi-Fi hotspot reports a failed state"
        else -> "Honda Wi-Fi hotspot state is unsupported: $state"
    }

    fun requireAccepted(result: Any?, operation: String) {
        check(result == true) { "$operation was rejected or returned no confirmation" }
    }
}
