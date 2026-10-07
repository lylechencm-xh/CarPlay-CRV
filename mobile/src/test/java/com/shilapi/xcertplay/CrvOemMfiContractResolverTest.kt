package com.shilapi.xcertplay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class CrvOemMfiContractResolverTest {
    @Test fun resolvesNamedFactoryContract() {
        val contract = CrvOemMfiContractResolver.resolve(NamedContract::class.java.methods.toList())
        assertNotNull(contract)
        assertEquals("getAccessoryCertificate", contract?.certificate?.name)
        assertEquals("signChallenge", contract?.signer?.name)
        assertEquals("getProtocolVersion", contract?.protocol?.name)
    }

    @Test fun resolvesObfuscatedMethodsOnlyWhenShapesAreUnique() {
        val contract = CrvOemMfiContractResolver.resolve(ObfuscatedContract::class.java.methods.toList())
        assertNotNull(contract)
        assertEquals("a", contract?.certificate?.name)
        assertEquals("b", contract?.signer?.name)
    }

    @Test fun rejectsAmbiguousByteArrayMethods() {
        assertNull(CrvOemMfiContractResolver.resolve(AmbiguousContract::class.java.methods.toList()))
    }

    private interface NamedContract {
        fun getAccessoryCertificate(maximumLength: Int): ByteArray
        fun signChallenge(challenge: ByteArray): ByteArray
        fun getProtocolVersion(): Int
    }

    private interface ObfuscatedContract {
        fun a(): ByteArray
        fun b(challenge: ByteArray): ByteArray
    }

    private interface AmbiguousContract {
        fun a(): ByteArray
        fun b(): ByteArray
        fun c(challenge: ByteArray): ByteArray
    }
}
