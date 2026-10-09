package com.shilapi.xcertplay.airplay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import java.net.Inet6Address
import java.net.InetAddress

class AirPlayAuxiliaryBindAddressTest {
    @Test
    fun reusesConcreteControlSocketAddressForAuxiliaryPorts() {
        val local = InetAddress.getByName("fe80::2")
        val peer = InetAddress.getByName("fe80::1")

        assertSame(local, airPlayAuxiliaryBindAddress(local, peer))
    }

    @Test
    fun preservesIpv6ScopeFromControlSocket() {
        val bytes = InetAddress.getByName("fe80::2").address
        val scoped = Inet6Address.getByAddress(null, bytes, 17)

        val selected = airPlayAuxiliaryBindAddress(
            scoped,
            InetAddress.getByName("fe80::1"),
        )

        assertEquals(17, (selected as Inet6Address).scopeId)
    }

    @Test
    fun fallsBackToIpv4WildcardForIpv4Peer() {
        val selected = airPlayAuxiliaryBindAddress(
            null,
            InetAddress.getByName("192.0.2.10"),
        )

        assertEquals("0.0.0.0", selected.hostAddress)
    }
}
