package com.shilapi.xcertplay.transport

import javax.net.ssl.SSLContext
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.jsse.provider.BouncyCastleJsseProvider
import org.junit.Assert.assertTrue
import org.junit.Test

class LockdownTlsProviderTest {
    @Test
    fun privateBcJsseProviderSupportsTls12WithoutGlobalRegistration() {
        val context = SSLContext.getInstance(
            "TLS",
            BouncyCastleJsseProvider(BouncyCastleProvider()),
        )
        context.init(null, null, null)

        assertTrue("TLSv1.2" in context.createSSLEngine().supportedProtocols)
    }
}
