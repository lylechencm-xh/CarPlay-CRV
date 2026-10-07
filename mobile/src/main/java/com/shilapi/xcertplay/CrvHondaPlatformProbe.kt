package com.shilapi.xcertplay

import android.app.ActivityManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.IBinder
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
            packages.take(MAX_MATCHES_PER_GROUP).forEach { packageName ->
                results += "Honda package candidate=" + packageName
                inspectPackage(packageName).forEach(results::add)
            }
        }

        val processes = runCatching {
            val activity = appContext.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            activity.runningAppProcesses.orEmpty()
                .filter { interesting(it.processName) }
                .distinctBy { it.pid }
                .sortedBy { it.processName }
                .take(MAX_MATCHES_PER_GROUP)
        }.getOrElse { emptyList() }
        processes.forEach { process ->
            results += "Honda process candidate=" + process.processName +
                " pid=" + process.pid + " uid=" + process.uid
            inspectProcess(process.pid).forEach(results::add)
        }

        val binderServices = runCatching {
            val serviceManager = Class.forName("android.os.ServiceManager")
            val listServices = serviceManager.getDeclaredMethod("listServices")
            (listServices.invoke(null) as? Array<*>)
                .orEmpty()
                .mapNotNull { it as? String }
                .distinct()
                .sorted()
        }.getOrElse { emptyList() }
        binderServices.filter(::interesting).take(MAX_MATCHES_PER_GROUP).forEach { name ->
            results += "Honda Binder candidate=" + name
            binderDescriptor(name)?.let { descriptor ->
                results += "Honda Binder descriptor name=" + name + " descriptor=" + descriptor
            }
        }
        binderServices.filter(::vendorInteresting).take(MAX_VENDOR_BINDER_MATCHES).forEach { name ->
            results += "Honda Binder vendor-candidate=" + name
            binderDescriptor(name)?.let { descriptor ->
                results += "Honda Binder descriptor name=" + name + " descriptor=" + descriptor
            }
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

    private fun inspectPackage(packageName: String): List<String> {
        val results = ArrayList<String>()
        val flags = PackageManager.GET_SERVICES or
            PackageManager.GET_RECEIVERS or
            PackageManager.GET_PROVIDERS or
            PackageManager.GET_PERMISSIONS
        val info = runCatching {
            appContext.packageManager.getPackageInfo(packageName, flags)
        }.getOrNull() ?: return results

        val app = info.applicationInfo
        results += "Honda package meta name=" + packageName +
            " uid=" + (app?.uid ?: -1) +
            " sharedUserId=" + (info.sharedUserId ?: "none") +
            " process=" + (app?.processName ?: "none") +
            " source=" + (app?.sourceDir ?: "unknown")

        info.requestedPermissions.orEmpty()
            .filter { permission ->
                val lower = permission.lowercase(Locale.US)
                lower.contains("usb") ||
                    lower.contains("car") ||
                    lower.contains("system") ||
                    lower.contains("signature") ||
                    lower.contains("camera") ||
                    lower.contains("media")
            }
            .take(MAX_COMPONENTS_PER_PACKAGE)
            .forEach { permission ->
                results += "Honda package permission name=" + packageName + " permission=" + permission
            }

        info.services.orEmpty().take(MAX_COMPONENTS_PER_PACKAGE).forEach { service ->
            results += "Honda service package=" + packageName + " class=" + service.name +
                " process=" + service.processName + " exported=" + service.exported +
                " permission=" + (service.permission ?: "none")
        }
        info.receivers.orEmpty().take(MAX_COMPONENTS_PER_PACKAGE).forEach { receiver ->
            results += "Honda receiver package=" + packageName + " class=" + receiver.name +
                " process=" + receiver.processName + " exported=" + receiver.exported +
                " permission=" + (receiver.permission ?: "none")
        }
        info.providers.orEmpty().take(MAX_COMPONENTS_PER_PACKAGE).forEach { provider ->
            results += "Honda provider package=" + packageName + " class=" + provider.name +
                " process=" + provider.processName + " exported=" + provider.exported +
                " authority=" + (provider.authority ?: "none")
        }
        return results
    }

    private fun inspectProcess(pid: Int): List<String> {
        val results = ArrayList<String>()
        val status = runCatching { File("/proc/" + pid + "/status").readLines() }
            .getOrElse { emptyList() }
        status.filter { line ->
            line.startsWith("Uid:") ||
                line.startsWith("Gid:") ||
                line.startsWith("Groups:") ||
                line.startsWith("CapEff:")
        }.take(8).forEach { line ->
            results += "Honda process status pid=" + pid + " " + line.trim()
        }

        val libraries = runCatching {
            File("/proc/" + pid + "/maps").useLines { lines ->
                lines.mapNotNull { line ->
                    val libraryPath = line.substringAfterLast(' ', "")
                    if (libraryPath.startsWith("/") && interesting(libraryPath)) libraryPath else null
                }.distinct().take(MAX_PROCESS_LIBRARIES).toList()
            }
        }.getOrElse { emptyList() }
        libraries.forEach { library ->
            results += "Honda process library pid=" + pid + " path=" + library
        }
        return results
    }

    private fun binderDescriptor(name: String): String? = runCatching {
        val serviceManager = Class.forName("android.os.ServiceManager")
        val checkService = serviceManager.getDeclaredMethod("checkService", String::class.java)
        val binder = checkService.invoke(null, name) as? IBinder ?: return@runCatching null
        binder.interfaceDescriptor?.take(MAX_VALUE)
    }.getOrNull()
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
        const val MAX_COMPONENTS_PER_PACKAGE = 48
        const val MAX_PROCESS_LIBRARIES = 48
        const val MAX_VALUE = 220
    }
}
