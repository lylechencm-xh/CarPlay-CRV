package com.shilapi.xcertplay

import java.io.File
import java.net.Inet6Address
import java.net.NetworkInterface
import java.util.Collections
import java.util.Locale

/** Read-only diagnostics for OEM USB/network drivers that may own the iPhone NCM interfaces. */
internal object CrvUsbKernelProbe {
    data class KernelNcmNetwork(
        val interfaceName: String,
        val linkLocal: Inet6Address,
        val hardwareAddress: ByteArray?,
    )

    fun waitForKernelNcm(timeoutMillis: Long): KernelNcmNetwork? {
        val deadline = System.nanoTime() + timeoutMillis.coerceAtLeast(0L) * 1_000_000L
        do {
            findKernelNcm()?.let { return it }
            if (System.nanoTime() >= deadline) return null
            try {
                Thread.sleep(KERNEL_NCM_POLL_MILLIS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return null
            }
        } while (true)
    }

    fun findKernelNcm(): KernelNcmNetwork? {
        if (!hasBoundCdcNcmDriver()) return null
        val networks = runCatching {
            Collections.list(NetworkInterface.getNetworkInterfaces())
        }.getOrElse { emptyList() }
        return networks
            .filter { network ->
                val lower = network.name.lowercase(Locale.US)
                (lower.startsWith("usb") || lower.contains("ncm")) &&
                    runCatching { network.isUp && !network.isLoopback }.getOrDefault(false)
            }
            .sortedWith(compareBy<NetworkInterface> {
                if (it.name.lowercase(Locale.US).startsWith("usb")) 0 else 1
            }.thenBy { it.name })
            .firstNotNullOfOrNull { network ->
                val linkLocal = runCatching {
                    Collections.list(network.inetAddresses)
                        .filterIsInstance<Inet6Address>()
                        .firstOrNull { it.isLinkLocalAddress }
                }.getOrNull() ?: return@firstNotNullOfOrNull null
                KernelNcmNetwork(
                    interfaceName = network.name,
                    linkLocal = linkLocal,
                    hardwareAddress = runCatching { network.hardwareAddress?.copyOf() }.getOrNull(),
                )
            }
    }

    private fun hasBoundCdcNcmDriver(): Boolean {
        val driver = File("/sys/bus/usb/drivers/cdc_ncm")
        return runCatching {
            driver.isDirectory && driver.listFiles().orEmpty().any {
                val name = it.name
                ":" in name && !name.startsWith(".")
            }
        }.getOrDefault(false)
    }
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
    private const val KERNEL_NCM_POLL_MILLIS = 100L
}
