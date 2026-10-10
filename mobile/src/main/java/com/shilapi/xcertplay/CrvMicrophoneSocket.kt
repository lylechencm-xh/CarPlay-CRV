package com.shilapi.xcertplay

import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress

/** Keep the session's address family and IPv6 scope; never reroute a wired stream to IPv4. */
internal fun openCrvMicrophoneSocket(
    localAddress: InetAddress?,
    report: (String) -> Unit,
    create: () -> DatagramSocket = { DatagramSocket(null) },
): DatagramSocket {
    var socket: DatagramSocket? = null
    var phase = "create"
    try {
        val bound = create()
        socket = bound
        phase = "reuse-address"
        bound.reuseAddress = true
        phase = "bind"
        // A missing local address uses the platform wildcard, without parsing a textual ::.
        bound.bind(InetSocketAddress(localAddress, 0))
        return bound
    } catch (error: Exception) {
        socket?.close()
        report("Microphone: UDP failed phase=$phase family=${localAddress?.javaClass?.simpleName ?: "wildcard"} " +
            "error=${error.javaClass.simpleName} message=${error.message.orEmpty().replace('\n', ' ').take(200)} " +
            "cause=${error.cause?.javaClass?.simpleName ?: "none"}")
        throw error
    }
}
