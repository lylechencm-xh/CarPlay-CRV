package com.shilapi.xcertplay

import com.shilapi.xcertplay.transport.LockdownPairRecord
import com.shilapi.xcertplay.transport.LockdownPairRecordGenerator
import com.shilapi.xcertplay.transport.LockdownTlsEngineFactory
import java.io.ByteArrayInputStream
import java.net.InetAddress
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.Security
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.interfaces.RSAPublicKey
import java.security.interfaces.RSAPrivateCrtKey
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.RSAPublicKeySpec
import java.math.BigInteger
import java.util.Date
import java.util.Base64
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509TrustManager
import javax.security.auth.x500.X500Principal
import org.bouncycastle.x509.X509V3CertificateGenerator
import org.bouncycastle.asn1.pkcs.RSAPublicKey as BcRsaPublicKey
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CrvLockdownTlsTest {
    @Suppress("DEPRECATION")
    private fun pairRecord(): LockdownPairRecord {
        val key = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }
            .generateKeyPair().public as RSAPublicKey
        val pem = "-----BEGIN RSA PUBLIC KEY-----\n" +
            Base64.getEncoder().encodeToString(BcRsaPublicKey(key.modulus, key.publicExponent).encoded) +
            "\n-----END RSA PUBLIC KEY-----\n"
        val record = LockdownPairRecordGenerator.generate(pem.toByteArray(Charsets.US_ASCII),
            "02:00:00:00:00:01", "HOST-ID", "SYSTEM-BUID")
        // Apple pairing uses an empty issuer DN that Android accepts but desktop SunX509
        // rejects. Give this JVM-only fixture a named issuer, retaining the generated key.
        val rootKey = parseRootKey(record) as RSAPrivateCrtKey
        val rootPublic = KeyFactory.getInstance("RSA").generatePublic(
            RSAPublicKeySpec(rootKey.modulus, rootKey.publicExponent),
        )
        val certificate = X509V3CertificateGenerator().apply {
            setSerialNumber(BigInteger.ONE)
            setIssuerDN(X500Principal("CN=Lockdown JVM Test"))
            setSubjectDN(X500Principal("CN=Lockdown JVM Test"))
            setNotBefore(Date(System.currentTimeMillis() - 60_000))
            setNotAfter(Date(System.currentTimeMillis() + 3_600_000))
            setPublicKey(rootPublic)
            setSignatureAlgorithm("SHA256withRSA")
        }.generate(rootKey)
        val rootPem = "-----BEGIN CERTIFICATE-----\n" +
            Base64.getEncoder().encodeToString(certificate.encoded) + "\n-----END CERTIFICATE-----\n"
        return LockdownPairRecord.restore(record.hostId, record.systemBuid, record.wifiMacAddress,
            record.devicePublicKeyPem, record.deviceCertificatePem, record.hostPrivateKeyPem,
            record.hostCertificatePem, record.rootPrivateKeyPem, rootPem.toByteArray(Charsets.US_ASCII))
    }

    private fun parseRootKey(record: LockdownPairRecord) = KeyFactory.getInstance("RSA").generatePrivate(
        PKCS8EncodedKeySpec(Base64.getDecoder().decode(String(record.rootPrivateKeyPem, Charsets.US_ASCII)
            .substringAfter("-----BEGIN PRIVATE KEY-----").substringBefore("-----END PRIVATE KEY-----")
            .filterNot(Char::isWhitespace))),
    )

    @Test fun unavailableDefaultKeyManagerAlgorithmStillBuildsSocketAndEngine() {
        val record = pairRecord()
        val original = Security.getProperty("ssl.KeyManagerFactory.algorithm")
        try {
            Security.setProperty("ssl.KeyManagerFactory.algorithm", "MissingHondaPKIX")
            assertTrue(LockdownTlsEngineFactory.create(record).useClientMode)
            assertTrue("TLSv1.2" in LockdownTlsEngineFactory.supportedSocketProtocols(record))
        } finally {
            Security.setProperty("ssl.KeyManagerFactory.algorithm", original)
        }
        // Parsing and zeroing temporary PEM buffers must not corrupt the saved identity.
        assertTrue(LockdownTlsEngineFactory.create(record).useClientMode)
    }

    @Test fun tls12HandshakePresentsPairedRootCertificate() {
        val record = pairRecord()
        val certificate = CertificateFactory.getInstance("X.509")
            .generateCertificate(ByteArrayInputStream(record.rootCertificatePem)) as X509Certificate
        val key = parseRootKey(record)
        val password = "test-only".toCharArray()
        val store = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
            load(null, password)
            setKeyEntry("server", key, password, arrayOf(certificate))
        }
        val manager = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(store, password) }
        val trust = object : X509TrustManager {
            override fun getAcceptedIssuers() = arrayOf(certificate)
            override fun checkClientTrusted(chain: Array<out X509Certificate>, authType: String) {
                assertArrayEquals(certificate.encoded, chain[0].encoded)
            }
            override fun checkServerTrusted(chain: Array<out X509Certificate>, authType: String) = Unit
        }
        val context = SSLContext.getInstance("TLS").apply { init(manager.keyManagers, arrayOf(trust), null) }
        val executor = Executors.newSingleThreadExecutor()
        val listener = context.serverSocketFactory.createServerSocket(0, 1, InetAddress.getLoopbackAddress()) as SSLServerSocket
        try {
            listener.soTimeout = 5000
            listener.enabledProtocols = arrayOf("TLSv1.2")
            listener.needClientAuth = true
            val server = executor.submit<ByteArray> {
                (listener.accept() as SSLSocket).use { socket ->
                    socket.soTimeout = 5000
                    socket.startHandshake()
                    socket.session.peerCertificates[0].encoded
                }
            }
            (LockdownTlsEngineFactory.createContext(record).socketFactory
                .createSocket(InetAddress.getLoopbackAddress(), listener.localPort) as SSLSocket).use { socket ->
                socket.soTimeout = 5000
                socket.enabledProtocols = arrayOf("TLSv1.2")
                socket.startHandshake()
                assertTrue(socket.session.protocol == "TLSv1.2")
            }
            assertArrayEquals(certificate.encoded, server.get(10, TimeUnit.SECONDS))
        } finally {
            listener.close()
            executor.shutdownNow()
        }
    }
}
