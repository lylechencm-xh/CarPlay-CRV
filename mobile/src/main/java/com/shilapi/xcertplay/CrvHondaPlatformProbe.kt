package com.shilapi.xcertplay

import android.app.ActivityManager
import android.content.Context
import java.io.File
import java.util.Locale

/**
 * Metadata-only probe for factory Honda/Mitsubishi CarPlay integration points.
 *
 * It does not open private app data, read library contents, invoke Binder transactions, or access
 * authentication material. Results are intended to identify package/service/library names worth
 * investigating on the real head unit.
 */
internal class CrvHondaPlatformProbe(context: Context) {
    private val appContext = context.applicationContext

    fun collect(): List<String> {
        val results = ArrayList<String>()
        results += "Honda platform probe fingerprint=" + android.os.Build.FINGERPRINT.take(MAX_VALUE)

        val packages = runCatching {
            appContext.packageManager.getInstalledPackages(0)
                .mapNotNull { it.packageName }
                .filter(::interesting)
                .sorted()
        }.getOrElse { emptyList() }
        if (packages.isEmpty()) {
            results += "Honda platform probe packages=no keyword matches"
        } else {
            packages.take(MAX_MATCHES_PER_GROUP).forEach {
                results += "Honda package candidate=$it"
            }
        }

        val processes = runCatching {
            val activity = appContext.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            activity.runningAppProcesses.orEmpty()
                .mapNotNull { it.processName }
                .filter(::interesting)
                .distinct()
                .sorted()
                .take(MAX_MATCHES_PER_GROUP)
        }.getOrElse { emptyList() }
        processes.forEach { results += "Honda process candidate=$it" }

        val binderServices = runCatching {
            val serviceManager = Class.forName("android.os.ServiceManager")
            val listServices = serviceManager.getDeclaredMethod("listServices")
            (listServices.invoke(null) as? Array<*>)
                .orEmpty()
                .mapNotNull { it as? String }
                .distinct()
                .sorted()
        }.getOrElse { emptyList() }
        binderServices.filter(::interesting).take(MAX_MATCHES_PER_GROUP).forEach {
            results += "Honda Binder candidate=$it"
        }
        binderServices.filter(::vendorInteresting).take(MAX_VENDOR_BINDER_MATCHES).forEach {
            results += "Honda Binder vendor-candidate=$it"
        }

        val selinux = runCatching {
            val clazz = Class.forName("android.os.SELinux")
            val enabled = clazz.getMethod("isSELinuxEnabled").invoke(null) as? Boolean
            val enforced = clazz.getMethod("isSELinuxEnforced").invoke(null) as? Boolean
            "enabled=$enabled enforced=$enforced"
        }.getOrElse { "unavailable" }
        results += "Honda SELinux $selinux"

        for (directory in SYSTEM_PATHS) {
            val matches = runCatching {
                File(directory).listFiles()
                    .orEmpty()
                    .map { it.name }
                    .filter(::interesting)
                    .sorted()
                    .take(MAX_MATCHES_PER_GROUP)
            }.getOrElse { emptyList() }
            if (matches.isNotEmpty()) {
                results += "Honda path=$directory candidates=" + matches.joinToString(",")
            }
        }

        val deviceNodes = runCatching {
            File("/dev").listFiles()
                .orEmpty()
                .filter { file ->
                    val lower = file.name.lowercase(Locale.US)
                    lower.startsWith("i2c-") || interesting(lower)
                }
                .sortedBy { it.name }
                .take(MAX_MATCHES_PER_GROUP)
        }.getOrElse { emptyList() }
        if (deviceNodes.isNotEmpty()) {
            results += "Honda /dev candidates=" + deviceNodes.joinToString(",") { it.name }
            deviceNodes.filter { it.name.startsWith("i2c-") }.forEach { node ->
                val metadata = CrvNativeDeviceProbe.stat(node.absolutePath)
                results += "Honda I2C metadata node=" + node.name + " " + metadata
            }
        }

        return results
    }

    private fun interesting(value: String): Boolean {
        val lower = value.lowercase(Locale.US)
        return KEYWORDS.any(lower::contains)
    }

    private fun vendorInteresting(value: String): Boolean {
        val lower = value.lowercase(Locale.US)
        return VENDOR_KEYWORDS.any(lower::contains) &&
            SERVICE_KEYWORDS.any(lower::contains)
    }

    private companion object {
        val KEYWORDS = listOf(
            "carplay",
            "iphone",
            "iap2",
            "iap",
            "mfi",
            "airplay",
            "apple",
            "projection",
            "smartphone",
            "accessory",
            "authentication",
            "auth",
        )
        val VENDOR_KEYWORDS = listOf(
            "honda",
            "mitsubishi",
            "melsc",
            "alpine",
            "clarion",
            "panasonic",
            "pioneer",
            "denso",
            "jvc",
            "kenwood",
        )
        val SERVICE_KEYWORDS = listOf(
            "service",
            "auth",
            "usb",
            "iap",
            "apple",
            "accessory",
            "phone",
            "smartphone",
        )
        val SYSTEM_PATHS = listOf(
            "/system/app",
            "/system/priv-app",
            "/system/framework",
            "/system/bin",
            "/system/lib",
            "/system/vendor/lib",
            "/vendor/app",
            "/vendor/lib",
        )
        const val MAX_MATCHES_PER_GROUP = 32
        const val MAX_VENDOR_BINDER_MATCHES = 48
        const val MAX_VALUE = 220
    }
}
