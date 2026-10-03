package com.shilapi.xcertplay.network

import java.net.Socket

/** API19-safe TCP liveness for the CR-V wired build. */
internal object TcpLiveness {
    fun configure(socket: Socket, diagnostic: (String) -> Unit) {
        try {
            socket.keepAlive = true
            diagnostic("TCP keepalive enabled (API19 compatibility mode)")
        } catch (error: Exception) {
            diagnostic("TCP keepalive unavailable: ${error.javaClass.simpleName}")
        }
    }
}
