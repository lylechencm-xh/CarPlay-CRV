package com.shilapi.xcertplay.network

import android.os.Build
import java.net.Socket

/** Detect a vanished peer, without treating a legitimately idle CarPlay screen as a failure. */
internal object TcpLiveness {
    fun configure(socket: Socket, diagnostic: (String) -> Unit) {
        socket.keepAlive = true
        if (Build.VERSION.SDK_INT >= 29) {
            TcpLivenessApi29.configure(socket, diagnostic)
        }
    }
}
