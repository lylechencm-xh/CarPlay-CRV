package com.shilapi.xcertplay

/** Read-only native metadata probe for device nodes. It never performs I2C transactions. */
internal object CrvNativeDeviceProbe {
    init {
        System.loadLibrary("xcertplay_i2c")
    }

    fun stat(path: String): String = runCatching { nativeStat(path) }
        .getOrElse { "stat-unavailable type=" + it.javaClass.simpleName }

    private external fun nativeStat(path: String): String
}
