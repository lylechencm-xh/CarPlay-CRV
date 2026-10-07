package com.shilapi.xcertplay

import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.IBinder
import com.shilapi.xcertplay.mfi.MfiAuthenticator
import com.shilapi.xcertplay.mfi.MfiInvalidDataException
import dalvik.system.DexFile
import dalvik.system.PathClassLoader
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.LinkedHashSet
import java.util.Locale

/**
 * Adapter for the factory Mitsubishi/Honda iAP authentication Binder service.
 *
 * The implementation deliberately avoids raw Binder transaction codes. It only calls methods
 * exposed by the factory AIDL Stub/Proxy when their Java signatures unambiguously match the
 * certificate/signature contract required by [MfiAuthenticator].
 */
internal object CrvOemMfiAuthenticator {
    data class Lease(
        val authenticator: MfiAuthenticator,
        val source: String,
    )

    fun acquire(context: Context, report: (String) -> Unit): Lease? {
        val binder = findBinder(report) ?: return null
        val binderDescriptor = runCatching { binder.interfaceDescriptor }
            .onFailure { error ->
                report("MFi OEM Binder descriptor failed type=" + error.javaClass.simpleName)
            }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }
        if (binderDescriptor != null) {
            report("MFi OEM Binder descriptor=" + binderDescriptor)
        } else {
            report("MFi OEM Binder link_iap_adapter has no readable interface descriptor")
        }

        val descriptors = LinkedHashSet<String>()
        binderDescriptor?.let(descriptors::add)
        descriptors += discoverFactoryInterfaces(context, report)
        if (descriptors.isEmpty()) {
            report("MFi OEM AIDL interface discovery returned no candidates")
            return null
        }

        val loaders = classLoaders(context, report)
        for (descriptor in descriptors) for ((label, loader) in loaders) {
            val resolved = resolveInterface(descriptor, binder, loader, report, label) ?: continue
            val methods = resolved.interfaceType.methods
                .filterNot { it.declaringClass == Any::class.java }
                .distinctBy { signature(it) }
                .sortedBy { signature(it) }
            methods.take(MAX_METHOD_LOGS).forEach { method ->
                report("MFi OEM method loader=" + label + " " + signature(method))
            }

            val contract = CrvOemMfiContractResolver.resolve(methods)
            if (contract == null) {
                report(
                    "MFi OEM Binder interface resolved but auth contract incomplete loader=" + label +
                        " descriptor=" + descriptor,
                )
                continue
            }
            report(
                "MFi OEM authentication ready service=" + SERVICE_NAME +
                    " descriptor=" + descriptor +
                    " certificateMethod=" + contract.certificate.name +
                    " signMethod=" + contract.signer.name +
                    " protocolMethod=" + (contract.protocol?.name ?: "assume-v3"),
            )
            return Lease(
                authenticator = ReflectionAuthenticator(
                    target = resolved.proxy,
                    certificateMethod = contract.certificate,
                    signMethod = contract.signer,
                    protocolMethod = contract.protocol,
                ),
                source = "oem-link-iap-adapter",
            )
        }
        return null
    }

    private data class Resolved(
        val interfaceType: Class<*>,
        val proxy: Any,
    )

    /**
     * The Honda service is registered before its Binder is always obtainable. `listServices()`
     * can therefore show link_iap_adapter while a single `checkService()` still returns null.
     * Try the blocking ServiceManager lookup as a fallback and wait for a short, bounded window;
     * this runs on the controller worker, never the UI thread.
     */
    private fun findBinder(report: (String) -> Unit): IBinder? {
        val lookup = runCatching {
            val manager = Class.forName("android.os.ServiceManager")
            val methods = listOf("checkService", "getService").map { name ->
                name to manager.getDeclaredMethod(name, String::class.java).apply {
                    isAccessible = true
                }
            }
            val listed = runCatching {
                val listServices = manager.getDeclaredMethod("listServices").apply { isAccessible = true }
                (listServices.invoke(null) as? Array<*>)?.any { it == SERVICE_NAME } == true
            }.getOrDefault(false)
            methods to listed
        }.onFailure { error ->
            report("MFi OEM Binder lookup setup failed type=" + error.javaClass.simpleName)
        }.getOrNull() ?: return null
        val methods = lookup.first
        val listed = lookup.second
        report("MFi OEM Binder service=$SERVICE_NAME registered=$listed")

        var lastFailure: Throwable? = null
        repeat(BINDER_LOOKUP_ATTEMPTS) { attempt ->
            for ((lookupName, method) in methods) {
                val binder = try {
                    method.invoke(null, SERVICE_NAME) as? IBinder
                } catch (error: Throwable) {
                    lastFailure = error.cause ?: error
                    null
                }
                if (binder != null) {
                    report(
                        "MFi OEM Binder service=$SERVICE_NAME present=true " +
                            "lookup=$lookupName attempt=${attempt + 1} " +
                            "alive=${binder.isBinderAlive} ping=${runCatching { binder.pingBinder() }.getOrDefault(false)}",
                    )
                    return binder
                }
            }
            if (attempt + 1 < BINDER_LOOKUP_ATTEMPTS) {
                try {
                    Thread.sleep(BINDER_LOOKUP_RETRY_MILLIS)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    report("MFi OEM Binder lookup interrupted")
                    return null
                }
            }
        }
        report(
            "MFi OEM Binder service=$SERVICE_NAME unavailable " +
                "registered=$listed attempts=$BINDER_LOOKUP_ATTEMPTS" +
                (lastFailure?.let { " type=${it.javaClass.simpleName}" } ?: ""),
        )
        return null
    }

    private fun classLoaders(
        context: Context,
        report: (String) -> Unit,
    ): List<Pair<String, ClassLoader>> {
        val out = ArrayList<Pair<String, ClassLoader>>()
        context.classLoader?.let { out += "app" to it }
        for (packageName in FACTORY_PACKAGES) {
            val sourcePath = trustedFactorySource(context, packageName, report) ?: continue
            val packageLoader = runCatching {
                context.createPackageContext(
                    packageName,
                    Context.CONTEXT_INCLUDE_CODE or Context.CONTEXT_IGNORE_SECURITY,
                ).classLoader
            }.onFailure { error ->
                report(
                    "MFi OEM package loader failed package=$packageName " +
                        "type=${error.javaClass.simpleName}",
                )
            }.getOrNull()
            if (packageLoader != null && out.none { it.second === packageLoader }) {
                out += packageName to packageLoader
            }

            val pathLoader = PathClassLoader(sourcePath, context.classLoader)
            out += "$packageName:apk" to pathLoader
        }
        return out
    }

    /** Discover only AIDL-style interfaces; no class is instantiated and no transaction is sent. */
    private fun discoverFactoryInterfaces(
        context: Context,
        report: (String) -> Unit,
    ): List<String> {
        val candidates = LinkedHashSet<String>()
        for (packageName in FACTORY_PACKAGES) {
            val sourcePath = trustedFactorySource(context, packageName, report) ?: continue
            try {
                val dex = DexFile(sourcePath)
                try {
                    val names = LinkedHashSet<String>()
                    val entries = dex.entries()
                    while (entries.hasMoreElements()) names += entries.nextElement()
                    names.asSequence()
                        .filter { it.endsWith("\$Stub") }
                        .map { it.removeSuffix("\$Stub") }
                        .filter { candidate ->
                            val lower = candidate.lowercase(Locale.US)
                            OEM_INTERFACE_KEYWORDS.any(lower::contains)
                        }
                        .filter { names.contains(it) }
                        .take(MAX_DISCOVERED_INTERFACES)
                        .forEach(candidates::add)
                } finally {
                    runCatching { dex.close() }
                }
            } catch (error: Throwable) {
                report(
                    "MFi OEM AIDL scan failed package=$packageName " +
                        "type=${error.javaClass.simpleName}",
                )
            }
        }
        candidates.forEach { report("MFi OEM AIDL candidate=" + it) }
        return candidates.toList()
    }

    private fun trustedFactorySource(
        context: Context,
        packageName: String,
        report: (String) -> Unit,
    ): String? {
        val info = runCatching {
            context.packageManager.getApplicationInfo(packageName, 0)
        }.onFailure { error ->
            report(
                "MFi OEM package lookup failed package=$packageName " +
                    "type=${error.javaClass.simpleName}",
            )
        }.getOrNull() ?: return null
        val source = info.sourceDir?.takeIf { it.isNotBlank() } ?: return null
        val trusted = info.flags and ApplicationInfo.FLAG_SYSTEM != 0 ||
            source.startsWith("/system/") || source.startsWith("/vendor/")
        if (!trusted) {
            report("MFi OEM package rejected as non-system package=$packageName source=$source")
            return null
        }
        return source
    }

    private fun resolveInterface(
        descriptor: String,
        binder: IBinder,
        loader: ClassLoader,
        report: (String) -> Unit,
        label: String,
    ): Resolved? = runCatching {
        val interfaceType = Class.forName(descriptor, true, loader)
        val stubType = Class.forName(descriptor + "\$Stub", true, loader)
        val asInterface = stubType.getDeclaredMethod("asInterface", IBinder::class.java)
        asInterface.isAccessible = true
        val proxy = asInterface.invoke(null, binder) ?: return@runCatching null
        Resolved(interfaceType, proxy)
    }.onFailure { error ->
        report(
            "MFi OEM interface load failed loader=" + label +
                " descriptor=" + descriptor +
                " type=" + error.javaClass.simpleName,
        )
    }.getOrNull()

    private fun signature(method: Method): String = buildString {
        append(method.returnType.simpleName)
        append(" ")
        append(method.name)
        append("(")
        append(method.parameterTypes.joinToString(",") { it.simpleName })
        append(")")
        if (Modifier.isStatic(method.modifiers)) append(" static")
    }

    private class ReflectionAuthenticator(
        private val target: Any,
        private val certificateMethod: Method,
        private val signMethod: Method,
        private val protocolMethod: Method?,
    ) : MfiAuthenticator {
        override fun protocolMajor(): Int {
            val method = protocolMethod ?: return DEFAULT_PROTOCOL_MAJOR
            val value = invoke(method)
            val major = when (value) {
                is Number -> value.toInt()
                else -> DEFAULT_PROTOCOL_MAJOR
            }
            return major.takeIf { it in 2..4 } ?: DEFAULT_PROTOCOL_MAJOR
        }

        override fun readCertificate(maximumOutputLength: Int): ByteArray {
            val value = if (certificateMethod.parameterTypes.isEmpty()) {
                invoke(certificateMethod)
            } else {
                invoke(certificateMethod, maximumOutputLength)
            }
            val bytes = value as? ByteArray
                ?: throw MfiInvalidDataException("OEM MFi certificate method returned non-byte-array")
            if (bytes.isEmpty() || bytes.size > maximumOutputLength) {
                throw MfiInvalidDataException(
                    "OEM MFi certificate length " + bytes.size +
                        " outside 1.." + maximumOutputLength,
                )
            }
            return bytes.copyOf()
        }

        override fun signChallenge(challenge: ByteArray): ByteArray {
            if (challenge.isEmpty() || challenge.size > MAX_CHALLENGE_BYTES) {
                throw MfiInvalidDataException("OEM MFi challenge length invalid: " + challenge.size)
            }
            val value = invoke(signMethod, challenge.copyOf())
            val bytes = value as? ByteArray
                ?: throw MfiInvalidDataException("OEM MFi sign method returned non-byte-array")
            if (bytes.isEmpty() || bytes.size > MAX_SIGNATURE_BYTES) {
                throw MfiInvalidDataException("OEM MFi signature length invalid: " + bytes.size)
            }
            return bytes.copyOf()
        }

        private fun invoke(method: Method, vararg args: Any): Any? = try {
            method.isAccessible = true
            method.invoke(target, *args)
        } catch (error: Throwable) {
            val cause = error.cause ?: error
            throw MfiInvalidDataException(
                "OEM MFi call " + method.name + " failed: " +
                    (cause.message ?: cause.javaClass.simpleName),
                cause,
            )
        }
    }

    private const val SERVICE_NAME = "link_iap_adapter"
    private val FACTORY_PACKAGES = listOf(
        "com.mitsubishielectric.ada.appservice.carplayapservice",
        "com.mitsubishielectric.ada.app.carplay",
    )
    private val OEM_INTERFACE_KEYWORDS = listOf("iap", "mfi", "auth", "carplay")
    private const val DEFAULT_PROTOCOL_MAJOR = 3
    private const val BINDER_LOOKUP_ATTEMPTS = 8
    private const val BINDER_LOOKUP_RETRY_MILLIS = 250L
    private const val MAX_DISCOVERED_INTERFACES = 32
    private const val MAX_CHALLENGE_BYTES = 128
    private const val MAX_SIGNATURE_BYTES = 4096
    private const val MAX_METHOD_LOGS = 96
}
