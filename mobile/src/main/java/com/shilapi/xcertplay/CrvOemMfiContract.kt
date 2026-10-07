package com.shilapi.xcertplay

import java.lang.reflect.Method
import java.util.Locale

internal data class CrvOemMfiContract(
    val certificate: Method,
    val signer: Method,
    val protocol: Method?,
)

/** Conservative reflection contract for vendor AIDL interfaces. */
internal object CrvOemMfiContractResolver {
    fun resolve(methods: List<Method>): CrvOemMfiContract? {
        val certificate = selectUnique(methods, ::namedCertificate, ::certificateShape) ?: return null
        val signer = selectUnique(methods, ::namedSigner, ::signerShape) ?: return null
        val protocol = methods.filter(::protocolMethod).singleOrNull()
        return CrvOemMfiContract(certificate, signer, protocol)
    }

    private fun namedCertificate(method: Method): Boolean =
        certificateShape(method) && method.name.lowercase(Locale.US).contains("cert")

    private fun certificateShape(method: Method): Boolean {
        if (method.returnType != ByteArray::class.java) return false
        val params = method.parameterTypes
        return params.isEmpty() ||
            (params.size == 1 &&
                (params[0] == Int::class.javaPrimitiveType || params[0] == Int::class.javaObjectType))
    }

    private fun namedSigner(method: Method): Boolean {
        if (!signerShape(method)) return false
        val lower = method.name.lowercase(Locale.US)
        return lower.contains("sign") || lower.contains("challenge") || lower.contains("response")
    }

    private fun signerShape(method: Method): Boolean =
        method.returnType == ByteArray::class.java &&
            method.parameterTypes.contentEquals(arrayOf(ByteArray::class.java))

    private fun protocolMethod(method: Method): Boolean {
        val lower = method.name.lowercase(Locale.US)
        if (!(lower.contains("protocol") || lower.contains("version"))) return false
        if (method.parameterTypes.isNotEmpty()) return false
        return method.returnType == Int::class.javaPrimitiveType ||
            method.returnType == Int::class.javaObjectType ||
            method.returnType == Byte::class.javaPrimitiveType ||
            method.returnType == Byte::class.javaObjectType
    }

    private fun selectUnique(
        methods: List<Method>,
        namedMatch: (Method) -> Boolean,
        shapeMatch: (Method) -> Boolean,
    ): Method? {
        val named = methods.filter(namedMatch)
        if (named.size == 1) return named.single()
        if (named.size > 1) return null
        return methods.filter(shapeMatch).singleOrNull()
    }
}
