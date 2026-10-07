package com.shilapi.xcertplay.transport

import android.annotation.SuppressLint
import java.io.ByteArrayInputStream
import java.net.Socket
import java.nio.charset.Charset
import java.security.GeneralSecurityException
import java.security.KeyFactory
import java.security.Principal
import java.security.PrivateKey
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.PKCS8EncodedKeySpec
import org.bouncycastle.util.encoders.Base64
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509ExtendedKeyManager
import javax.net.ssl.X509TrustManager

/**
 * Builds an unstarted client TLS engine for the dedicated USB Lockdown channel.
 *
 * Matching the locked transport behavior, the engine does not authenticate the peer certificate.
 * It must not be reused for internet or general-purpose TLS connections.
 */
object LockdownTlsEngineFactory {
    @Throws(GeneralSecurityException::class)
    fun create(pairRecord: LockdownPairRecord): SSLEngine {
        return createContext(pairRecord).createSSLEngine(PEER_HOST, PEER_PORT).apply {
            useClientMode = true
            // SSLEngine has no hostname verification enabled by default. Avoid the
            // SSLParameters endpoint-identification setter, which is API24 on Android.
        }
    }

    @Throws(GeneralSecurityException::class)
    fun createContext(pairRecord: LockdownPairRecord): SSLContext {
        // Present the same root identity on session and service TLS channels.
        val privateKeyPem = pairRecord.rootPrivateKeyPem
        val certificatePem = pairRecord.rootCertificatePem
        var privateKeyDer: ByteArray? = null
        try {
            privateKeyDer = decodePkcs8Pem(privateKeyPem)
            val privateKey = KeyFactory.getInstance("RSA")
                .generatePrivate(PKCS8EncodedKeySpec(privateKeyDer))
            val certificate = CertificateFactory.getInstance("X.509")
                .generateCertificate(ByteArrayInputStream(certificatePem)) as X509Certificate
            // Honda API17 firmware can advertise PKIX without providing its factory.
            // There is exactly one paired identity, so no keystore/factory is necessary.
            return createContext(arrayOf(PairedIdentityKeyManager(privateKey, certificate)))
        } finally {
            privateKeyPem.fill(0)
            certificatePem.fill(0)
            privateKeyDer?.fill(0)
        }
    }

    @Throws(GeneralSecurityException::class)
    fun supportedSocketProtocols(pairRecord: LockdownPairRecord): Array<String> {
        val socket = createContext(pairRecord).socketFactory.createSocket() as SSLSocket
        return try {
            socket.supportedProtocols.copyOf()
        } finally {
            try { socket.close() } catch (_: Exception) { }
        }
    }

    private fun createContext(keyManagers: Array<javax.net.ssl.KeyManager>): SSLContext =
        SSLContext.getInstance("TLS").apply {
            init(keyManagers, arrayOf(UsbLockdownTrustManager), null)
        }

    private class PairedIdentityKeyManager(
        private val key: PrivateKey,
        private val certificate: X509Certificate,
    ) : X509ExtendedKeyManager() {
        private fun matches(keyType: String?, issuers: Array<out Principal>?): Boolean =
            keyType == key.algorithm &&
                (issuers.isNullOrEmpty() || issuers.any { it == certificate.issuerX500Principal })

        override fun getClientAliases(keyType: String?, issuers: Array<out Principal>?): Array<String>? =
            if (matches(keyType, issuers)) arrayOf(KEY_ALIAS) else null

        override fun chooseClientAlias(
            keyType: Array<out String>?, issuers: Array<out Principal>?, socket: Socket?,
        ): String? = if (keyType?.any { matches(it, issuers) } == true) KEY_ALIAS else null

        override fun chooseEngineClientAlias(
            keyType: Array<out String>?, issuers: Array<out Principal>?, engine: SSLEngine?,
        ): String? = chooseClientAlias(keyType, issuers, null)

        override fun getCertificateChain(alias: String?): Array<X509Certificate>? =
            if (alias == KEY_ALIAS) arrayOf(certificate) else null

        override fun getPrivateKey(alias: String?): PrivateKey? = if (alias == KEY_ALIAS) key else null

        // This identity is only used as a client on the dedicated USB channel.
        override fun getServerAliases(keyType: String?, issuers: Array<out Principal>?): Array<String>? = null
        override fun chooseServerAlias(keyType: String?, issuers: Array<out Principal>?, socket: Socket?): String? = null
        override fun chooseEngineServerAlias(keyType: String?, issuers: Array<out Principal>?, engine: SSLEngine?): String? = null
    }

    private fun decodePkcs8Pem(pem: ByteArray): ByteArray {
        val begin = pem.indexOf(BEGIN_PRIVATE_KEY)
        val end = pem.indexOf(END_PRIVATE_KEY, begin + BEGIN_PRIVATE_KEY.size)
        if (begin < 0 || end < 0) throw GeneralSecurityException("Invalid PKCS#8 private key PEM")
        val encoded = pem.copyOfRange(begin + BEGIN_PRIVATE_KEY.size, end)
        return try {
            Base64.decode(encoded)
        } catch (error: IllegalArgumentException) {
            throw GeneralSecurityException("Invalid PKCS#8 private key PEM", error)
        } finally {
            encoded.fill(0)
        }
    }

    private fun ByteArray.indexOf(needle: ByteArray, startIndex: Int = 0): Int {
        if (needle.isEmpty()) return startIndex.coerceIn(0, size)
        for (offset in startIndex.coerceAtLeast(0)..size - needle.size) {
            var matches = true
            for (index in needle.indices) {
                if (this[offset + index] != needle[index]) {
                    matches = false
                    break
                }
            }
            if (matches) return offset
        }
        return -1
    }

    @SuppressLint("CustomX509TrustManager", "TrustAllX509TrustManager")
    private object UsbLockdownTrustManager : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit

        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit

        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }

    private const val KEY_ALIAS = "lockdown-host"
    private const val PEER_HOST = "Device"
    private const val PEER_PORT = 0
    private val BEGIN_PRIVATE_KEY = "-----BEGIN PRIVATE KEY-----".toByteArray(Charset.forName("US-ASCII"))
    private val END_PRIVATE_KEY = "-----END PRIVATE KEY-----".toByteArray(Charset.forName("US-ASCII"))
}
