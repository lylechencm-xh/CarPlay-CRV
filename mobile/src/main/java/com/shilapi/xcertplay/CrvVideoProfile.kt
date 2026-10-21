package com.shilapi.xcertplay

/** Immutable for an Activity/session. Camera resizing never renegotiates the encoded canvas. */
internal enum class CrvVideoProfile(val width: Int, val height: Int) {
    STANDARD(1280, 720), LOW_LOAD(960, 540);
    companion object {
        fun fromStored(value: String?) = values().firstOrNull { it.name == value } ?: STANDARD
    }
}
