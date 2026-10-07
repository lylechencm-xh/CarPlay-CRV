package com.shilapi.xcertplay

import android.app.ActivityManager
import android.content.Context
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Environment
import android.os.StatFs
import java.io.File
import java.net.NetworkInterface
import java.util.Collections
import java.util.Locale

/**
 * Read-only, privacy-bounded head-unit inventory for field diagnostics.
 *
 * It intentionally excludes serial numbers, MAC addresses, accounts, user files and application
 * private data. Values are capped so a malformed proc/sysfs entry cannot grow the field log.
 */
internal class CrvSystemInfoProbe(context: Context) {
    private val appContext = context.applicationContext

    fun collect(): List<String> {
        val out = ArrayList<String>()
        collectBuild(out)
        collectProperties(out)
        collectCpu(out)
        collectMemory(out)
        collectStorage(out)
        collectProcess(out)
        collectFeatures(out)
        collectUsb(out)
        collectNetwork(out)
        collectMounts(out)
        return out
    }

    private fun collectBuild(out: MutableList<String>) {
        out += "System build " +
            "manufacturer=${safe(Build.MANUFACTURER)} brand=${safe(Build.BRAND)} " +
            "model=${safe(Build.MODEL)} product=${safe(Build.PRODUCT)} " +
            "device=${safe(Build.DEVICE)} board=${safe(Build.BOARD)} hardware=${safe(Build.HARDWARE)}"
        out += "System Android " +
            "release=${safe(Build.VERSION.RELEASE)} sdk=${Build.VERSION.SDK_INT} " +
            "id=${safe(Build.ID)} display=${safe(Build.DISPLAY)} " +
            "type=${safe(Build.TYPE)} tags=${safe(Build.TAGS)}"
        out += "System ABI primary=${safe(Build.CPU_ABI)} secondary=${safe(Build.CPU_ABI2)} " +
            "arch=${safe(System.getProperty("os.arch"))}"
        out += "System runtime processors=${Runtime.getRuntime().availableProcessors()} " +
            "java=${safe(System.getProperty("java.version"))}"
    }

    private fun collectProperties(out: MutableList<String>) {
        for (key in PROPERTY_KEYS) {
            val value = systemProperty(key)
            if (!value.isNullOrBlank()) out += "System property $key=${safe(value)}"
        }
    }

    private fun collectCpu(out: MutableList<String>) {
        val version = readSmall(File("/proc/version"))
        if (version != null) out += "System kernel=" + safe(version)

        val cpuLines = readLines(File("/proc/cpuinfo"), MAX_PROC_LINES)
            .filter { line ->
                val key = line.substringBefore(':').trim().lowercase(Locale.US)
                key in CPUINFO_KEYS
            }
            .distinct()
            .take(MAX_CPU_LINES)
        cpuLines.forEach { out += "System cpu " + safe(it) }
    }

    private fun collectMemory(out: MutableList<String>) {
        val info = ActivityManager.MemoryInfo()
        val manager = appContext.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        runCatching { manager.getMemoryInfo(info) }
        out += "System memory availBytes=${info.availMem} thresholdBytes=${info.threshold} low=${info.lowMemory}"
        readLines(File("/proc/meminfo"), 16)
            .filter { it.startsWith("MemTotal:") || it.startsWith("MemFree:") || it.startsWith("Cached:") }
            .forEach { out += "System meminfo " + safe(it) }
    }

    private fun collectStorage(out: MutableList<String>) {
        storage("data", appContext.filesDir, out)
        storage("external", appContext.getExternalFilesDir(null), out)
        storage("root", Environment.getRootDirectory(), out)
    }

    @Suppress("DEPRECATION")
    private fun storage(label: String, file: File?, out: MutableList<String>) {
        if (file == null) return
        val stat = runCatching { StatFs(file.absolutePath) }.getOrNull() ?: return
        val blockSize = stat.blockSize.toLong()
        val total = stat.blockCount.toLong() * blockSize
        val available = stat.availableBlocks.toLong() * blockSize
        out += "System storage $label totalBytes=$total availableBytes=$available"
    }

    private fun collectProcess(out: MutableList<String>) {
        val status = readLines(File("/proc/self/status"), 128)
        for (prefix in listOf("Uid:", "Gid:", "Groups:", "CapEff:", "TracerPid:")) {
            status.firstOrNull { it.startsWith(prefix) }?.let {
                out += "System process " + safe(it)
            }
        }
    }

    private fun collectFeatures(out: MutableList<String>) {
        val pm = appContext.packageManager
        for (feature in FEATURE_NAMES) {
            out += "System feature $feature=${pm.hasSystemFeature(feature)}"
        }
    }

    private fun collectUsb(out: MutableList<String>) {
        val manager = appContext.getSystemService(Context.USB_SERVICE) as UsbManager
        val devices = runCatching { manager.deviceList.values.toList() }.getOrElse { emptyList() }
        out += "System USB devices=${devices.size}"
        devices.sortedBy { it.deviceId }.take(MAX_USB_DEVICES).forEach { device ->
            out += "System USB device vid=0x${device.vendorId.toString(16)} " +
                "pid=0x${device.productId.toString(16)} class=${device.deviceClass}/" +
                "${device.deviceSubclass}/${device.deviceProtocol} interfaces=${device.interfaceCount} " +
                "permission=${runCatching { manager.hasPermission(device) }.getOrDefault(false)}"
            for (index in 0 until minOf(device.interfaceCount, MAX_USB_INTERFACES)) {
                val iface = device.getInterface(index)
                out += "System USB iface#$index id=${iface.id} " +
                    "class=${iface.interfaceClass}/${iface.interfaceSubclass}/${iface.interfaceProtocol} " +
                    "endpoints=${iface.endpointCount}"
            }
        }
    }

    private fun collectNetwork(out: MutableList<String>) {
        val interfaces = runCatching {
            Collections.list(NetworkInterface.getNetworkInterfaces()).sortedBy { it.name }
        }.getOrElse { emptyList() }
        interfaces.take(MAX_NETWORK_INTERFACES).forEach { network ->
            val addresses = runCatching {
                Collections.list(network.inetAddresses)
                    .mapNotNull { address ->
                        val raw = address.hostAddress ?: return@mapNotNull null
                        raw.take(MAX_VALUE)
                    }
            }.getOrElse { emptyList() }
            out += "System net iface=${safe(network.name)} up=${runCatching { network.isUp }.getOrDefault(false)} " +
                "loopback=${runCatching { network.isLoopback }.getOrDefault(false)} " +
                "mtu=${runCatching { network.mtu }.getOrDefault(-1)} addresses=${addresses.joinToString(",")}"
        }
    }

    private fun collectMounts(out: MutableList<String>) {
        readLines(File("/proc/mounts"), MAX_MOUNT_LINES)
            .filter { line -> MOUNT_PREFIXES.any { marker -> line.contains(" $marker ") } }
            .take(MAX_MOUNT_RESULTS)
            .forEach { out += "System mount " + safe(it) }
    }

    private fun systemProperty(key: String): String? = runCatching {
        val clazz = Class.forName("android.os.SystemProperties")
        clazz.getMethod("get", String::class.java).invoke(null, key) as? String
    }.getOrNull()

    private fun readSmall(file: File): String? = runCatching {
        if (!file.isFile) return@runCatching null
        file.inputStream().bufferedReader().use { reader ->
            reader.readLine()?.take(MAX_VALUE)
        }
    }.getOrNull()

    private fun readLines(file: File, limit: Int): List<String> = runCatching {
        if (!file.isFile) return@runCatching emptyList<String>()
        file.inputStream().bufferedReader().use { reader ->
            val lines = ArrayList<String>()
            while (lines.size < limit) {
                val line = reader.readLine() ?: break
                lines += line.take(MAX_VALUE)
            }
            lines
        }
    }.getOrElse { emptyList() }

    private fun safe(value: String?): String =
        value.orEmpty().replace('\n', ' ').replace('\r', ' ').take(MAX_VALUE)

    private companion object {
        val PROPERTY_KEYS = listOf(
            "ro.board.platform",
            "ro.boot.hardware",
            "ro.hardware",
            "ro.product.board",
            "ro.product.device",
            "ro.product.name",
            "ro.build.version.incremental",
            "ro.build.version.release",
            "ro.build.version.sdk",
            "ro.secure",
            "ro.debuggable",
            "ro.adb.secure",
            "ro.kernel.android.checkjni",
            "persist.sys.usb.config",
            "sys.usb.config",
            "sys.usb.state",
        )
        val CPUINFO_KEYS = setOf(
            "processor", "model name", "hardware", "revision", "cpu architecture",
            "cpu implementer", "cpu part", "features",
        )
        val FEATURE_NAMES = listOf(
            "android.hardware.usb.host",
            "android.hardware.wifi",
            "android.hardware.bluetooth",
            "android.hardware.touchscreen",
            "android.hardware.audio.output",
            "android.hardware.microphone",
            "android.hardware.location.gps",
        )
        val MOUNT_PREFIXES = listOf(
            "/", "/system", "/data", "/cache", "/vendor", "/storage", "/mnt",
        )
        const val MAX_VALUE = 320
        const val MAX_PROC_LINES = 256
        const val MAX_CPU_LINES = 32
        const val MAX_MOUNT_LINES = 256
        const val MAX_MOUNT_RESULTS = 32
        const val MAX_USB_DEVICES = 16
        const val MAX_USB_INTERFACES = 16
        const val MAX_NETWORK_INTERFACES = 24
    }
}
