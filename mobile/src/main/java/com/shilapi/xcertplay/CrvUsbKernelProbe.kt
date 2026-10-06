package com.shilapi.xcertplay

import java.io.File
import java.net.NetworkInterface
import java.util.Collections
import java.util.Locale

/** Read-only diagnostics for OEM USB/network drivers that may own the iPhone NCM interfaces. */
internal object CrvUsbKernelProbe {
    fun collect(): List<String> {
        val lines = ArrayList<String>()

        val driverRoot = File("/sys/bus/usb/drivers")
        val driverNames = runCatching {
            driverRoot.listFiles()
                .orEmpty()
                .filter { it.isDirectory && interestingDriver(it.name) }
                .sortedBy { it.name }
        }.getOrElse { emptyList() }

        for (driver in driverNames) {
            val bindings = runCatching {
                driver.listFiles()
                    .orEmpty()
                    .map { it.name }
                    .filter { ":" in it || "-" in it }
                    .sorted()
                    .take(MAX_ITEMS)
            }.getOrElse { emptyList() }
            lines += "USB kernel driver ${driver.name} bindings=" +
                if (bindings.isEmpty()) "none" else bindings.joinToString(",")
        }

        val interfaces = runCatching {
            Collections.list(NetworkInterface.getNetworkInterfaces())
                .sortedBy { it.name }
                .map { network ->
                    val addresses = Collections.list(network.inetAddresses)
                        .joinToString(",") { it.hostAddress ?: "?" }
                    "${network.name}(up=${network.isUp},loop=${network.isLoopback},addr=$addresses)"
                }
                .filter { summary ->
                    val lower = summary.lowercase(Locale.US)
                    lower.contains("usb") ||
                        lower.contains("ncm") ||
                        lower.contains("eth") ||
                        lower.contains("enx")
                }
                .take(MAX_ITEMS)
        }.getOrElse { emptyList() }

        if (interfaces.isNotEmpty()) {
            lines += "USB/network interfaces=" + interfaces.joinToString(" ")
        }
        if (lines.isEmpty()) lines += "USB kernel probe=no visible NCM/usbnet metadata"
        return lines
    }

    private fun interestingDriver(name: String): Boolean {
        val lower = name.lowercase(Locale.US)
        return lower.contains("cdc_ncm") ||
            lower.contains("cdc_ether") ||
            lower.contains("usbnet") ||
            lower.contains("ipheth") ||
            lower.contains("rndis") ||
            lower.contains("cdc_mbim")
    }

    private const val MAX_ITEMS = 24
}
