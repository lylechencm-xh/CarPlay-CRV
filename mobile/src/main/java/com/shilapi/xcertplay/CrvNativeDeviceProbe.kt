package com.shilapi.xcertplay

/** Native diagnostics for device nodes. It never performs I2C transactions. */
internal object CrvNativeDeviceProbe {
    init {
        System.loadLibrary("xcertplay_i2c")
    }

    fun stat(path: String): String = runCatching { nativeStat(path) }
        .getOrElse { "stat-unavailable type=" + it.javaClass.simpleName }

    /** Opens and immediately closes the node to report the kernel permission result. */
    fun openAccess(path: String): String = runCatching { nativeOpenAccess(path) }
        .getOrElse { "open-probe-unavailable type=" + it.javaClass.simpleName }

    private external fun nativeStat(path: String): String
    private external fun nativeOpenAccess(path: String): String
}
