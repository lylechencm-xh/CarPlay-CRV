package com.shilapi.xcertplay

import java.io.File
import java.net.Inet6Address
import java.net.NetworkInterface
import java.util.Collections
import java.util.Locale

/** Diagnostics plus narrowly-scoped bring-up for OEM USB/NCM interfaces owned by the current iPhone. */
internal object CrvUsbKernelProbe {
    data class ExpectedUsbNcm(
        val configurationValue: Int,
        val interfaceNumbers: Set<Int>,
        val vendorId: Int,
        val productId: Int,
        val busNumber: Int?,
        val deviceNumber: Int?,
    )

    data class KernelNcmNetwork(
        val interfaceName: String,
        val linkLocal: Inet6Address,
        val hardwareAddress: ByteArray?,
        val kernelConfigurationValue: Int,
        val usbInterfaceNumber: Int,
        val sysfsInterfaceName: String,
        val usbDevicePath: String,
    )

    fun waitForKernelNcm(
        expected: ExpectedUsbNcm,
        timeoutMillis: Long,
    ): KernelNcmNetwork? {
        val deadline = System.nanoTime() + timeoutMillis.coerceAtLeast(0L) * 1_000_000L
        var previous: KernelNcmNetwork? = null
        var stableSamples = 0
        do {
            val current = findKernelNcm(expected)
            if (
                current != null &&
                previous?.interfaceName == current.interfaceName &&
                previous.linkLocal == current.linkLocal &&
                previous.kernelConfigurationValue == current.kernelConfigurationValue &&
                previous.usbInterfaceNumber == current.usbInterfaceNumber
            ) {
                stableSamples += 1
                if (stableSamples >= REQUIRED_STABLE_SAMPLES) return current
            } else {
                stableSamples = if (current == null) 0 else 1
            }
            previous = current
            if (System.nanoTime() >= deadline) return null
            try {
                Thread.sleep(KERNEL_NCM_POLL_MILLIS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return null
            }
        } while (true)
    }

    fun findKernelNcm(expected: ExpectedUsbNcm): KernelNcmNetwork? {
        val networks = runCatching {
            Collections.list(NetworkInterface.getNetworkInterfaces())
        }.getOrElse { emptyList() }

        return networks
            .filter { network ->
                val lower = network.name.lowercase(Locale.US)
                (lower.startsWith("usb") || lower.contains("ncm")) &&
                    runCatching { network.isUp && !network.isLoopback }.getOrDefault(false) &&
                    hasCarrier(network.name)
            }
            .sortedWith(compareBy<NetworkInterface> {
                if (it.name.lowercase(Locale.US).startsWith("usb")) 0 else 1
            }.thenBy { it.name })
            .firstNotNullOfOrNull { network ->
                inspectNetwork(network, expected)
            }
    }

    fun findConfigurationConflict(expected: ExpectedUsbNcm): String? {
        val networkNames = runCatching {
            File("/sys/class/net").listFiles().orEmpty().map { it.name }
        }.getOrElse { emptyList() }
        for (name in networkNames.sorted()) {
            val interfacePath = runCatching {
                File("/sys/class/net/$name/device").canonicalFile
            }.getOrNull()?.takeIf { it.exists() } ?: continue
            val driverName = runCatching {
                File(interfacePath, "driver").canonicalFile.name
            }.getOrNull() ?: continue
            if (driverName != "cdc_ncm") continue

            val usbDevice = findUsbDeviceParent(interfacePath) ?: continue
            val vendor = readHex(File(usbDevice, "idVendor")) ?: continue
            val product = readHex(File(usbDevice, "idProduct")) ?: continue
            val bus = readInt(File(usbDevice, "busnum"))
            val dev = readInt(File(usbDevice, "devnum"))
            if (vendor != expected.vendorId || product != expected.productId) continue
            if (expected.busNumber != null && bus != null && bus != expected.busNumber) continue
            if (expected.deviceNumber != null && dev != null && dev != expected.deviceNumber) continue

            val parsed = parseUsbInterfaceName(interfacePath.name)
            val kernelConfiguration = readInt(File(usbDevice, "bConfigurationValue"))
            val sysfsConfiguration = parsed?.first
            val interfaceNumber = parsed?.second
            val matches = kernelConfiguration == expected.configurationValue &&
                sysfsConfiguration == expected.configurationValue &&
                interfaceNumber in expected.interfaceNumbers
            if (!matches) {
                return "net=$name driver=$driverName sysfs=${interfacePath.name} " +
                    "kernelCfg=${kernelConfiguration ?: -1} " +
                    "sysfsCfg=${sysfsConfiguration ?: -1} " +
                    "iface=${interfaceNumber ?: -1} " +
                    "expectedCfg=${expected.configurationValue} " +
                    "expectedIfaces=${expected.interfaceNumbers.sorted()} " +
                    "usb=${hex4(vendor)}:${hex4(product)} bus=${bus ?: -1} dev=${dev ?: -1}"
            }
        }
        return null
    }

    fun describeExpected(expected: ExpectedUsbNcm): String =
        "expected cfg=${expected.configurationValue} ifaces=${expected.interfaceNumbers.sorted()} " +
            "usb=${hex4(expected.vendorId)}:${hex4(expected.productId)} " +
            "bus=${expected.busNumber ?: -1} dev=${expected.deviceNumber ?: -1}"

    data class KernelBringUpResult(
        val interfaceName: String?,
        val attempted: Boolean,
        val resultCode: Int?,
        val error: String?,
    ) {
        val successful: Boolean get() = interfaceName != null && error == null && resultCode == 0
    }

    /**
     * Android 4.2.x exposes hidden NetworkUtils.enableInterface(), backed by ifc_enable().
     * Try it only after the sysfs USB identity proves this netdev belongs to the current iPhone
     * CarPlay configuration. Permission/capability failures are non-fatal; callers can fall back
     * to the userspace NCM bridge.
     */
    fun tryBringUpKernelNcm(expected: ExpectedUsbNcm): KernelBringUpResult {
        val interfaceName = findBoundKernelNcmInterface(expected)
            ?: return KernelBringUpResult(null, false, null, "no matching cdc_ncm netdev")
        val network = runCatching { NetworkInterface.getByName(interfaceName) }.getOrNull()
        if (network != null && runCatching { network.isUp }.getOrDefault(false)) {
            return KernelBringUpResult(interfaceName, false, 0, null)
        }

        return try {
            val networkUtils = Class.forName("android.net.NetworkUtils")
            val enable = networkUtils.getDeclaredMethod("enableInterface", String::class.java)
            enable.isAccessible = true
            val code = (enable.invoke(null, interfaceName) as? Number)?.toInt()
            KernelBringUpResult(interfaceName, true, code, null)
        } catch (error: Throwable) {
            val cause = error.cause ?: error
            KernelBringUpResult(
                interfaceName = interfaceName,
                attempted = true,
                resultCode = null,
                error = cause.javaClass.simpleName + ": " + cause.message.orEmpty(),
            )
        }
    }

    private fun findBoundKernelNcmInterface(expected: ExpectedUsbNcm): String? {
        val names = runCatching {
            File("/sys/class/net").listFiles().orEmpty().map { it.name }
        }.getOrElse { emptyList() }
        for (name in names.sorted()) {
            val interfacePath = runCatching {
                File("/sys/class/net/$name/device").canonicalFile
            }.getOrNull()?.takeIf { it.exists() } ?: continue
            val driverName = runCatching {
                File(interfacePath, "driver").canonicalFile.name
            }.getOrNull() ?: continue
            if (driverName != "cdc_ncm") continue

            val parsed = parseUsbInterfaceName(interfacePath.name) ?: continue
            if (parsed.first != expected.configurationValue) continue
            if (parsed.second !in expected.interfaceNumbers) continue

            val usbDevice = findUsbDeviceParent(interfacePath) ?: continue
            val vendor = readHex(File(usbDevice, "idVendor")) ?: continue
            val product = readHex(File(usbDevice, "idProduct")) ?: continue
            val bus = readInt(File(usbDevice, "busnum"))
            val dev = readInt(File(usbDevice, "devnum"))
            val kernelConfiguration = readInt(File(usbDevice, "bConfigurationValue"))
            if (vendor != expected.vendorId || product != expected.productId) continue
            if (kernelConfiguration != expected.configurationValue) continue
            if (expected.busNumber != null && bus != null && bus != expected.busNumber) continue
            if (expected.deviceNumber != null && dev != null && dev != expected.deviceNumber) continue
            return name
        }
        return null
    }

    private fun inspectNetwork(
        network: NetworkInterface,
        expected: ExpectedUsbNcm,
    ): KernelNcmNetwork? {
        val linkLocal = runCatching {
            Collections.list(network.inetAddresses)
                .filterIsInstance<Inet6Address>()
                .firstOrNull { it.isLinkLocalAddress }
        }.getOrNull() ?: return null

        val interfacePath = runCatching {
            File("/sys/class/net/${network.name}/device").canonicalFile
        }.getOrNull()?.takeIf { it.exists() } ?: return null

        val driverName = runCatching {
            File(interfacePath, "driver").canonicalFile.name
        }.getOrNull() ?: return null
        if (driverName != "cdc_ncm") return null

        val sysfsInterfaceName = interfacePath.name
        val parsed = parseUsbInterfaceName(sysfsInterfaceName) ?: return null
        val sysfsConfiguration = parsed.first
        val sysfsInterfaceNumber = parsed.second

        val usbDevice = findUsbDeviceParent(interfacePath) ?: return null
        val kernelConfiguration = readInt(File(usbDevice, "bConfigurationValue")) ?: return null
        val vendor = readHex(File(usbDevice, "idVendor")) ?: return null
        val product = readHex(File(usbDevice, "idProduct")) ?: return null
        val bus = readInt(File(usbDevice, "busnum"))
        val dev = readInt(File(usbDevice, "devnum"))

        if (vendor != expected.vendorId || product != expected.productId) return null
        if (expected.busNumber != null && bus != null && bus != expected.busNumber) return null
        if (expected.deviceNumber != null && dev != null && dev != expected.deviceNumber) return null
        if (kernelConfiguration != expected.configurationValue) return null
        if (sysfsConfiguration != expected.configurationValue) return null
        if (sysfsInterfaceNumber !in expected.interfaceNumbers) return null

        return KernelNcmNetwork(
            interfaceName = network.name,
            linkLocal = linkLocal,
            hardwareAddress = runCatching { network.hardwareAddress?.copyOf() }.getOrNull(),
            kernelConfigurationValue = kernelConfiguration,
            usbInterfaceNumber = sysfsInterfaceNumber,
            sysfsInterfaceName = sysfsInterfaceName,
            usbDevicePath = usbDevice.absolutePath,
        )
    }

    private fun findUsbDeviceParent(start: File): File? {
        var current: File? = start
        repeat(MAX_PARENT_DEPTH) {
            val node = current ?: return null
            if (
                File(node, "idVendor").isFile &&
                File(node, "idProduct").isFile &&
                File(node, "bConfigurationValue").isFile
            ) {
                return node
            }
            current = node.parentFile
        }
        return null
    }

    private fun parseUsbInterfaceName(name: String): Pair<Int, Int>? {
        val colon = name.lastIndexOf(':')
        val dot = name.lastIndexOf('.')
        if (colon < 0 || dot <= colon + 1 || dot >= name.length - 1) return null
        val configuration = name.substring(colon + 1, dot).toIntOrNull() ?: return null
        val interfaceNumber = name.substring(dot + 1).toIntOrNull() ?: return null
        return configuration to interfaceNumber
    }

    private fun readInt(file: File): Int? =
        runCatching { file.readText().trim().toInt() }.getOrNull()

    private fun readHex(file: File): Int? =
        runCatching { file.readText().trim().toInt(16) }.getOrNull()

    private fun readText(file: File): String? =
        runCatching { file.readText().trim().takeIf { it.isNotEmpty() } }.getOrNull()

    private fun hasCarrier(interfaceName: String): Boolean {
        val carrier = File("/sys/class/net/$interfaceName/carrier")
        if (!carrier.exists()) return true
        return runCatching { carrier.readText().trim() == "1" }.getOrDefault(false)
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
                    val sysfs = runCatching {
                        val path = File("/sys/class/net/${network.name}/device").canonicalFile
                        val driver = File(path, "driver").canonicalFile.name
                        "${path.name}/$driver"
                    }.getOrDefault("?")
                    val carrier = readText(File("/sys/class/net/${network.name}/carrier"))
                    val operstate = readText(File("/sys/class/net/${network.name}/operstate"))
                    val disableIpv6 = readText(File("/proc/sys/net/ipv6/conf/${network.name}/disable_ipv6"))
                    val autoconf = readText(File("/proc/sys/net/ipv6/conf/${network.name}/autoconf"))
                    "${network.name}(up=${network.isUp},loop=${network.isLoopback}," +
                        "carrier=${carrier ?: "?"},oper=${operstate ?: "?"}," +
                        "ipv6Disabled=${disableIpv6 ?: "?"},autoconf=${autoconf ?: "?"}," +
                        "addr=$addresses,sysfs=$sysfs)"
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

    private fun hex4(value: Int): String = "%04x".format(Locale.US, value and 0xffff)

    private const val MAX_ITEMS = 24
    private const val MAX_PARENT_DEPTH = 8
    private const val KERNEL_NCM_POLL_MILLIS = 100L
    private const val REQUIRED_STABLE_SAMPLES = 3
}
