package com.shilapi.xcertplay

import java.net.DatagramSocket
import java.net.InetAddress
import java.net.Inet6Address
import java.net.InetSocketAddress
import java.net.SocketAddress
import java.net.SocketException
import org.junit.Assert.*
import org.junit.Test

class CrvMicrophoneSocketTest {
    @Test fun preservesTheLocalIpv6InterfaceScopeWithoutIpv4Fallback() {
        val bytes = ByteArray(16).also { it[0] = 0xfe.toByte(); it[1] = 0x80.toByte(); it[15] = 2 }
        val local = Inet6Address.getByAddress(null, bytes, 7)
        var target: SocketAddress? = null
        val socket = object : DatagramSocket(null as SocketAddress?) {
            override fun bind(address: SocketAddress?) { target = address }
        }
        try {
            assertSame(socket, openCrvMicrophoneSocket(local, {}) { socket })
            assertSame(local, (target as InetSocketAddress).address)
            assertEquals(7, ((target as InetSocketAddress).address as Inet6Address).scopeId)
        } finally { socket.close() }
    }

    @Test fun closesSocketWhenBindFailsAndReportsThePhase() {
        val socket = object : DatagramSocket(null as SocketAddress?) {
            override fun bind(address: SocketAddress?) { throw SocketException("test bind failure") }
        }
        val messages = mutableListOf<String>()
        try {
            openCrvMicrophoneSocket(InetAddress.getByName("127.0.0.1"), messages::add) { socket }
            fail("Expected bind failure")
        } catch (_: SocketException) {
            assertTrue(socket.isClosed)
            assertTrue(messages.single().contains("phase=bind"))
            assertTrue(messages.single().contains("test bind failure"))
        }
    }

    @Test fun bindsSessionLocalAddressAndKeepsSocketOpen() {
        val local = InetAddress.getByName("127.0.0.1")
        val socket = openCrvMicrophoneSocket(local, {})
        try {
            assertEquals(local, socket.localAddress)
            assertTrue(socket.localPort > 0)
            assertFalse(socket.isClosed)
        } finally { socket.close() }
    }
}
