package com.shilapi.xcertplay.transport

import java.nio.charset.StandardCharsets
import java.security.KeyPairGenerator
import java.security.interfaces.RSAPublicKey
import java.util.Base64
import org.bouncycastle.asn1.pkcs.RSAPublicKey as BcRsaPublicKey
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [19], manifest = Config.NONE)
class LockdownTlsEngineFactoryTest {
    @Test
    fun pairRecordBuildsClientTlsEngineWithTls12() {
        val keyPair = KeyPairGenerator.getInstance("RSA").apply {
            initialize(2048)
        }.generateKeyPair()
        val publicKey = keyPair.public as RSAPublicKey
        val der = BcRsaPublicKey(publicKey.modulus, publicKey.publicExponent).encoded
        val body = Base64.getMimeEncoder(
            64,
            "\n".toByteArray(StandardCharsets.US_ASCII),
        ).encodeToString(der)
        val pem = (
            "-----BEGIN RSA PUBLIC KEY-----\n" +
                body +
                "\n-----END RSA PUBLIC KEY-----\n"
            ).toByteArray(StandardCharsets.US_ASCII)

        val pairRecord = LockdownPairRecordGenerator.generate(
            devicePublicKeyPkcs1Pem = pem,
            wifiAddress = "02:00:00:00:00:01",
            hostId = "HOST-ID",
            systemBuid = "SYSTEM-BUID",
        )

        val engine = LockdownTlsEngineFactory.create(pairRecord)

        assertTrue(engine.useClientMode)
        assertTrue("TLSv1.2" in engine.supportedProtocols)
    }
}
