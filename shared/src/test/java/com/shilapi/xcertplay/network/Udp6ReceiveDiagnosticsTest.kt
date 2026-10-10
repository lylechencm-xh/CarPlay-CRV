package com.shilapi.xcertplay.network

import org.junit.Assert.*
import org.junit.Test

class Udp6ReceiveDiagnosticsTest {
    private fun row(inode: String = "123", drops: Long = 4) =
        "0: 00000000000000000000000000000000:1388 00000000000000000000000000000000:0000 07 " +
            "00000000:00000000 00:00000000 00000000 1000 0 $inode 2 00000000 $drops"

    @Test fun socketReadingsRequireUniquePortAndAvailableDropColumn() {
        assertEquals("123" to 4L, Udp6ReceiveDiagnostics.parseSocket(row(), 5000))
        assertNull(Udp6ReceiveDiagnostics.parseSocket(row(), 5001))
        assertNull(Udp6ReceiveDiagnostics.parseSocket(row() + "\n" + row("456"), 5000))
        assertNull(Udp6ReceiveDiagnostics.parseSocket(row().substringBeforeLast(' '), 5000))
        assertNull(Udp6ReceiveDiagnostics.parseSocket(null, 5000))
    }

    @Test fun deltasRemainUnknownAcrossMissingReadingsResetsAndSocketReplacement() {
        var system: String? = "Udp6InDatagrams 100\nUdp6InErrors 5\nUdp6RcvbufErrors 3"
        var socket: String? = row()
        val lines = mutableListOf<String>()
        val stats = Udp6ReceiveDiagnostics(5000, "test", { lines += it }, {
            if (it.endsWith("snmp6")) system else socket
        })
        stats.sample()
        assertTrue(lines.last().contains("socketDrops=unknown"))
        system = "Udp6InDatagrams 110\nUdp6InErrors 7\nUdp6RcvbufErrors 4"
        socket = row(drops = 6)
        stats.sample()
        assertTrue(lines.last().contains("socketDrops=2"))
        assertTrue(lines.last().contains("systemScope=allSockets Udp6InDatagrams=10 Udp6InErrors=2 Udp6RcvbufErrors=1"))
        system = null
        socket = null
        stats.sample()
        system = "Udp6InErrors 1"
        socket = row("456", 0)
        stats.sample()
        assertTrue(lines.last().contains("socketDrops=unknown"))
        assertTrue(lines.last().contains("Udp6InErrors=unknown"))
        system = "Udp6InErrors 0"
        stats.sample()
        assertTrue(lines.last().contains("Udp6InErrors=unknown"))
        socket = row("789", 9)
        stats.sample()
        assertTrue(lines.last().contains("socketDrops=unknown"))
    }
}
