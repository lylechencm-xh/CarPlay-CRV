package com.shilapi.xcertplay

import android.content.Context
import android.hardware.usb.UsbManager
import android.os.SystemClock
import java.io.File
import java.net.NetworkInterface
import java.util.Collections

/**
 * Read-only dynamic snapshot around critical CarPlay transitions.
 *
 * It captures transport, kernel and OEM runtime metadata but never reads credential bodies,
 * private application data or user documents.
 */
internal class CrvRuntimeSnapshotProbe(context: Context) {
    private val appContext = context.applicationContext

    fun collect(reason: String): List<String> {
        val out = ArrayList<String>()
        out += "Snapshot begin reason=" + safe(reason) + " uptimeMs=" + SystemClock.elapsedRealtime()
        collectUsb(out)
        collectNetwork(out)
        collectProc(out)
        collectSysfs(out)
        collectProcesses(out)
        out += "Snapshot end reason=" + safe(reason)
        return out
    }

    private fun collectUsb(out: MutableList<String>) {
        val manager = appContext.getSystemService(Context.USB_SERVICE) as UsbManager
        val devices = runCatching { manager.deviceList.values.toList() }.getOrElse { emptyList() }
        out += "Snapshot USB count=" + devices.size
        devices.sortedBy { it.deviceId }.forEach { device ->
            val permission = runCatching { manager.hasPermission(device) }.getOrDefault(false)
            out += "Snapshot USB vid=0x" + device.vendorId.toString(16) +
                " pid=0x" + device.productId.toString(16) +
                " id=" + device.deviceId +
                " permission=" + permission +
                " interfaces=" + device.interfaceCount +
                " note=no-extra-usbfs-open-for-diagnostics"
            for (index in 0 until device.interfaceCount) {
                val iface = device.getInterface(index)
                out += "Snapshot USB iface index=" + index +
                    " id=" + iface.id +
                    " class=" + iface.interfaceClass + "/" + iface.interfaceSubclass + "/" + iface.interfaceProtocol +
                    " endpoints=" + iface.endpointCount
                for (epIndex in 0 until iface.endpointCount) {
                    val ep = iface.getEndpoint(epIndex)
                    out += "Snapshot USB ep iface=" + iface.id +
                        " index=" + epIndex +
                        " addr=0x" + ep.address.toString(16) +
                        " dir=" + ep.direction +
                        " type=" + ep.type +
                        " maxPacket=" + ep.maxPacketSize +
                        " interval=" + ep.interval
                }
            }
        }
    }

    private fun collectNetwork(out: MutableList<String>) {
        val interfaces = runCatching {
            Collections.list(NetworkInterface.getNetworkInterfaces()).sortedBy { it.name }
        }.getOrElse { emptyList() }
        interfaces.forEach { network ->
            val addresses = runCatching {
                Collections.list(network.inetAddresses).mapNotNull { it.hostAddress }
            }.getOrElse { emptyList() }
            out += "Snapshot net iface=" + safe(network.name) +
                " up=" + runCatching { network.isUp }.getOrDefault(false) +
                " loop=" + runCatching { network.isLoopback }.getOrDefault(false) +
                " mtu=" + runCatching { network.mtu }.getOrDefault(-1) +
                " addrs=" + addresses.joinToString(",")
            val base = File("/sys/class/net/" + network.name)
            for (name in SYS_NET_FILES) {
                readOne(File(base, name))?.let { value ->
                    out += "Snapshot net sysfs iface=" + safe(network.name) + " " + name + "=" + safe(value)
                }
            }
        }
    }

    private fun collectProc(out: MutableList<String>) {
        for (entry in PROC_FILES) {
            val file = File(entry.first)
            readLines(file, entry.second).forEach { line ->
                out += "Snapshot proc path=" + entry.first + " " + safe(line)
            }
        }
    }

    private fun collectSysfs(out: MutableList<String>) {
        for (path in SYSFS_DIRS) {
            val files = runCatching { File(path).listFiles().orEmpty().sortedBy { it.name } }
                .getOrElse { emptyList() }
            files.take(MAX_SYSFS_ENTRIES).forEach { file ->
                val value = if (file.isFile) readOne(file) else null
                out += "Snapshot sysfs path=" + file.absolutePath +
                    " type=" + if (file.isDirectory) "dir" else "file" +
                    (if (value != null) " value=" + safe(value) else "")
            }
        }
    }

    private fun collectProcesses(out: MutableList<String>) {
        val proc = File("/proc").listFiles().orEmpty()
            .filter { it.name.all(Char::isDigit) }
            .sortedBy { it.name.toIntOrNull() ?: Int.MAX_VALUE }
        proc.forEach { dir ->
            val cmdline = readOne(File(dir, "cmdline"))?.replace('\u0000', ' ')?.trim().orEmpty()
            if (cmdline.isEmpty()) return@forEach
            val lower = cmdline.lowercase(java.util.Locale.US)
            if (!PROCESS_KEYWORDS.any(lower::contains)) return@forEach
            out += "Snapshot process pid=" + dir.name + " cmd=" + safe(cmdline)
            readLines(File(dir, "status"), 64)
                .filter { line ->
                    line.startsWith("Uid:") || line.startsWith("Gid:") ||
                        line.startsWith("Groups:") || line.startsWith("CapEff:") ||
                        line.startsWith("State:") || line.startsWith("Threads:")
                }
                .forEach { line -> out += "Snapshot process pid=" + dir.name + " " + safe(line) }
        }
    }

    private fun readOne(file: File): String? = runCatching {
        if (!file.isFile || !file.canRead()) return@runCatching null
        file.inputStream().bufferedReader().use { it.readLine()?.take(MAX_VALUE) }
    }.getOrNull()

    private fun readLines(file: File, limit: Int): List<String> = runCatching {
        if (!file.isFile || !file.canRead()) return@runCatching emptyList<String>()
        file.inputStream().bufferedReader().use { reader ->
            val lines = ArrayList<String>()
            while (lines.size < limit) {
                val line = reader.readLine() ?: break
                lines += line.take(MAX_VALUE)
            }
            lines
        }
    }.getOrElse { emptyList() }

    private fun safe(value: String): String = value.replace('\n', ' ').replace('\r', ' ').take(MAX_VALUE)

    private companion object {
        val SYS_NET_FILES = listOf("operstate", "carrier", "mtu", "type", "flags", "ifindex")
        val PROC_FILES = listOf(
            "/proc/cmdline" to 8,
            "/proc/modules" to 256,
            "/proc/filesystems" to 128,
            "/proc/interrupts" to 256,
            "/proc/net/dev" to 128,
            "/proc/net/route" to 128,
            "/proc/net/ipv6_route" to 128,
            "/proc/net/if_inet6" to 128,
            "/proc/net/arp" to 128,
            "/proc/net/tcp" to 128,
            "/proc/net/tcp6" to 128,
            "/proc/net/udp" to 128,
            "/proc/net/udp6" to 128,
        )
        val SYSFS_DIRS = listOf(
            "/sys/class/android_usb",
            "/sys/class/udc",
            "/sys/class/usb_device",
            "/sys/class/net",
            "/sys/bus/usb/devices",
            "/sys/bus/i2c/devices",
            "/sys/class/i2c-dev",
        )
        val PROCESS_KEYWORDS = listOf(
            "carplay", "iap", "apple", "mfi", "usb", "media", "mitsubishi", "honda", "ada",
        )
        const val MAX_VALUE = 1024
        const val MAX_SYSFS_ENTRIES = 256
    }
}