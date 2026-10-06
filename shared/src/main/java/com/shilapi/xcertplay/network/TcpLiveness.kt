package com.shilapi.xcertplay.network

import android.os.Build
import java.net.Socket

/** Detect a vanished peer, without treating a legitimately idle CarPlay screen as a failure. */
internal object TcpLiveness {
    fun configure(socket: Socket, diagnostic: (String) -> Unit) {
        socket.keepAlive = true
        if (Build.VERSION.SDK_INT >= 29) {
            runCatching {
                val helperClass = Class.forName("com.shilapi.xcertplay.network.TcpLivenessApi29")
                val helper = helperClass.getField("INSTANCE").get(null)
                val method = helperClass.methods.first {
                    it.name == "configure" && it.parameterTypes.size == 2
                }
                method.invoke(helper, socket, diagnostic)
            }.onFailure {
                diagnostic("TCP peer health tuning unavailable: ${it.javaClass.simpleName}")
            }
        }
    }
}
