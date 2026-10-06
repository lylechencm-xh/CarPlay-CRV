package com.shilapi.xcertplay.transport

import android.annotation.SuppressLint
import java.io.ByteArrayInputStream
import java.nio.charset.Charset
import java.security.GeneralSecurityException
import java.security.KeyFactory
import java.security.KeyStore
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.PKCS8EncodedKeySpec
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.jsse.provider.BouncyCastleJsseProvider
import org.bouncycastle.util.encoders.Base64
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
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
        val password = charArrayOf('l', 'o', 'c', 'k', 'd', 'o', 'w', 'n')
        // Lockdown presents the root identity from the pair record for both the session and
        // service TLS channels. HostCertificate is part of pairing, not this TLS identity.
        val privateKeyPem = pairRecord.rootPrivateKeyPem
        val certificatePem = pairRecord.rootCertificatePem
        var privateKeyDer: ByteArray? = null
        try {
            privateKeyDer = decodePkcs8Pem(privateKeyPem)
            val privateKey = KeyFactory.getInstance("RSA")
                .generatePrivate(PKCS8EncodedKeySpec(privateKeyDer))
            val certificate = CertificateFactory.getInstance("X.509")
                .generateCertificate(ByteArrayInputStream(certificatePem)) as X509Certificate
            val keyStore = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
                load(null, password)
                setKeyEntry(KEY_ALIAS, privateKey, password, arrayOf(certificate))
            }
            val keyManagers = createKeyManagers(keyStore, password)
            // Android 4.2.2 / API17 platform SSLEngine only exposes TLSv1. Modern iOS
            // Lockdown requires TLSv1.2+, so use a private BCJSSE provider instead of
            // globally modifying the head unit's security-provider list.
            val context = SSLContext.getInstance(
                "TLS",
                BouncyCastleJsseProvider(BouncyCastleProvider()),
            ).apply {
                init(keyManagers, arrayOf(UsbLockdownTrustManager), null)
            }
            return context.createSSLEngine(PEER_HOST, PEER_PORT).apply {
                useClientMode = true
                // SSLEngine has no hostname verification enabled by default. Avoid the
                // SSLParameters endpoint-identification setter, which is API24 on Android.
            }
        } finally {
            password.fill('\u0000')
            privateKeyPem.fill(0)
            certificatePem.fill(0)
            privateKeyDer?.fill(0)
        }
    }

    private fun createKeyManagers(
        keyStore: KeyStore,
        password: CharArray,
    ): Array<javax.net.ssl.KeyManager> {
        val algorithms = linkedSetOf<String>().apply {
            KeyManagerFactory.getDefaultAlgorithm()
                ?.takeIf { it.isNotBlank() }
                ?.let(::add)
            add("X509")
            add("PKIX")
            add("SunX509")
        }
        var lastFailure: GeneralSecurityException? = null
        for (algorithm in algorithms) {
            try {
                val factory = KeyManagerFactory.getInstance(algorithm)
                factory.init(keyStore, password)
                return factory.keyManagers
            } catch (error: GeneralSecurityException) {
                lastFailure = error
            }
        }
        throw GeneralSecurityException(
            "No compatible KeyManagerFactory implementation: " + algorithms.joinToString(","),
            lastFailure,
        )
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
