package com.shilapi.xcertplay.network

import java.io.File

/** Optional Linux counters. Unavailable readings never become zero or bridge over a reset. */
internal class Udp6ReceiveDiagnostics(
    private val port: Int,
    private val label: String,
    private val report: (String) -> Unit,
    private val read: (String) -> String? = { path -> runCatching { File(path).readText() }.getOrNull() },
) {
    private var previousSystem: Map<String, Long>? = null
    private var previousSocket: Pair<String, Long>? = null

    fun sample() {
        val system = parseSystem(read("/proc/net/snmp6"))
        val socket = parseSocket(read("/proc/net/udp6"), port)
        val systemText = KEYS.joinToString(" ") { key ->
            "$key=${delta(system?.get(key), previousSystem?.get(key))}"
        }
        val drops = if (socket != null && socket.first == previousSocket?.first) {
            delta(socket.second, previousSocket?.second)
        } else "unknown"
        previousSystem = system
        previousSocket = socket
        runCatching { report("UDP6 receive $label socketDrops=$drops systemScope=allSockets $systemText") }
    }

    private fun delta(current: Long?, previous: Long?): String =
        if (current != null && previous != null && current >= previous) (current - previous).toString()
        else "unknown"

    internal companion object {
        private val KEYS = listOf("Udp6InDatagrams", "Udp6InErrors", "Udp6RcvbufErrors")

        fun parseSystem(text: String?): Map<String, Long>? = text?.lineSequence()?.mapNotNull { line ->
            val fields = line.trim().split(Regex("\\s+"))
            if (fields.size != 2 || fields[0] !in KEYS) null
            else fields[1].toLongOrNull()?.takeIf { it >= 0 }?.let { fields[0] to it }
        }?.toMap()

        fun parseSocket(text: String?, port: Int): Pair<String, Long>? {
            val matches = text?.lineSequence()?.mapNotNull { line ->
                val fields = line.trim().split(Regex("\\s+"))
                if (fields.size < 13 || fields[1].substringAfterLast(':').toIntOrNull(16) != port) null
                else fields.last().toLongOrNull()?.takeIf { it >= 0 }?.let { fields[9] to it }
            }?.toList() ?: return null
            // Reused ports are ambiguous; do not attribute another socket's losses to this stream.
            return matches.singleOrNull()
        }
    }
}
