package com.shilapi.xcertplay

import android.content.Context
import android.os.IBinder
import com.shilapi.xcertplay.mfi.MfiAuthenticator
import com.shilapi.xcertplay.mfi.MfiInvalidDataException
import java.lang.reflect.Method
import java.lang.reflect.Modifier
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
        val descriptor = runCatching { binder.interfaceDescriptor }.getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?: run {
                report("MFi OEM Binder link_iap_adapter has no interface descriptor")
                return null
            }
        report("MFi OEM Binder descriptor=" + descriptor)

        val loaders = classLoaders(context)
        for ((label, loader) in loaders) {
            val resolved = resolveInterface(descriptor, binder, loader, report, label) ?: continue
            val methods = resolved.interfaceType.methods
                .filterNot { it.declaringClass == Any::class.java }
                .distinctBy { signature(it) }
                .sortedBy { signature(it) }
            methods.take(MAX_METHOD_LOGS).forEach { method ->
                report("MFi OEM method loader=" + label + " " + signature(method))
            }

            val certificate = methods.firstOrNull(::isCertificateMethod)
            val signer = methods.firstOrNull(::isSignMethod)
            val protocol = methods.firstOrNull(::isProtocolMethod)
            if (certificate == null || signer == null) {
                report(
                    "MFi OEM Binder interface resolved but auth contract incomplete loader=" + label +
                        " certificate=" + (certificate?.name ?: "none") +
                        " signer=" + (signer?.name ?: "none"),
                )
                continue
            }
            report(
                "MFi OEM authentication ready service=" + SERVICE_NAME +
                    " descriptor=" + descriptor +
                    " certificateMethod=" + certificate.name +
                    " signMethod=" + signer.name +
                    " protocolMethod=" + (protocol?.name ?: "assume-v3"),
            )
            return Lease(
                authenticator = ReflectionAuthenticator(
                    target = resolved.proxy,
                    certificateMethod = certificate,
                    signMethod = signer,
                    protocolMethod = protocol,
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

    private fun findBinder(report: (String) -> Unit): IBinder? = runCatching {
        val manager = Class.forName("android.os.ServiceManager")
        val method = manager.getDeclaredMethod("checkService", String::class.java)
        method.invoke(null, SERVICE_NAME) as? IBinder
    }.onFailure { error ->
        report("MFi OEM Binder lookup failed type=" + error.javaClass.simpleName)
    }.getOrNull()?.also {
        report("MFi OEM Binder service=" + SERVICE_NAME + " present=true")
    }

    private fun classLoaders(context: Context): List<Pair<String, ClassLoader>> {
        val out = ArrayList<Pair<String, ClassLoader>>()
        context.classLoader?.let { out += "app" to it }
        for (packageName in FACTORY_PACKAGES) {
            val loader = runCatching {
                context.createPackageContext(
                    packageName,
                    Context.CONTEXT_INCLUDE_CODE or Context.CONTEXT_IGNORE_SECURITY,
                ).classLoader
            }.getOrNull()
            if (loader != null && out.none { it.second === loader }) {
                out += packageName to loader
            }
        }
        return out
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

    private fun isCertificateMethod(method: Method): Boolean {
        if (method.returnType != ByteArray::class.java) return false
        val lower = method.name.lowercase(Locale.US)
        if (!lower.contains("cert")) return false
        val params = method.parameterTypes
        return params.isEmpty() ||
            (params.size == 1 && (params[0] == Int::class.javaPrimitiveType || params[0] == Integer::class.java))
    }

    private fun isSignMethod(method: Method): Boolean {
        if (method.returnType != ByteArray::class.java) return false
        val lower = method.name.lowercase(Locale.US)
        if (!(lower.contains("sign") || lower.contains("challenge"))) return false
        val params = method.parameterTypes
        return params.size == 1 && params[0] == ByteArray::class.java
    }

    private fun isProtocolMethod(method: Method): Boolean {
        val lower = method.name.lowercase(Locale.US)
        if (!(lower.contains("protocol") || lower.contains("version"))) return false
        if (method.parameterTypes.isNotEmpty()) return false
        return method.returnType == Int::class.javaPrimitiveType ||
            method.returnType == Integer::class.java ||
            method.returnType == Byte::class.javaPrimitiveType ||
            method.returnType == java.lang.Byte::class.java
    }

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
    private const val DEFAULT_PROTOCOL_MAJOR = 3
    private const val MAX_CHALLENGE_BYTES = 128
    private const val MAX_SIGNATURE_BYTES = 4096
    private const val MAX_METHOD_LOGS = 96
}