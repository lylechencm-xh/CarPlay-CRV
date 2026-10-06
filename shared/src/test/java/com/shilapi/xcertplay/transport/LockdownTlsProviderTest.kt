package com.shilapi.xcertplay.transport

import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import org.junit.Assert.assertTrue
import org.junit.Test

class LockdownTlsProviderTest {
    @Test
    fun platformTlsSocketSupportsTls12() {
        val context = SSLContext.getInstance("TLS")
        context.init(null, null, null)
        val socket = context.socketFactory.createSocket() as SSLSocket
        try {
            assertTrue("TLSv1.2" in socket.supportedProtocols)
        } finally {
            socket.close()
        }
    }
}
