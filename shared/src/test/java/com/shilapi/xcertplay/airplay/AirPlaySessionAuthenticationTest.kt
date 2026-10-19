package com.shilapi.xcertplay.airplay

import java.net.Socket
import org.junit.Assert.*
import org.junit.Test

class AirPlaySessionAuthenticationTest {
    @Test fun mediaAndCommandsRequireCompletedPairVerification() {
        var activated = 0
        val session = session { activated++ }
        try {
            for ((method, path) in listOf("SETUP" to "/", "RECORD" to "/", "TEARDOWN" to "/", "POST" to "/command")) {
                assertEquals(403, handle(session, method, path).status)
            }
            // A shared secret from M2 is not proof that the peer's M3 signature was checked.
            PairVerify::class.java.getDeclaredField("sharedSecret").apply { isAccessible = true }
                .set(session.pairVerify, ByteArray(32))
            assertEquals(403, handle(session, "RECORD", "/").status)
            assertEquals(0, activated)
        } finally { session.close() }
    }

    @Test fun verifiedPeerCanActivateTheSession() {
        var activated = 0
        val session = session { activated++ }
        try {
            PairVerify::class.java.getDeclaredField("verified").apply { isAccessible = true }
                .setBoolean(session.pairVerify, true)
            assertEquals(200, handle(session, "RECORD", "/").status)
            assertEquals(1, activated)
        } finally { session.close() }
    }

    private fun handle(session: AirPlaySession, method: String, path: String): RtspMessage.Response =
        AirPlaySession::class.java.getDeclaredMethod("handle", RtspMessage.Request::class.java)
            .apply { isAccessible = true }
            .invoke(session, RtspMessage.Request(method, path, "RTSP/1.0", emptyMap(), byteArrayOf())) as RtspMessage.Response

    private fun session(activated: () -> Unit) = AirPlaySession(
        socket = Socket(),
        config = AirPlayConfig(deviceName = "test", deviceId = "02:00:00:00:00:02", btMac = "02:00:00:00:00:01",
            sourceVersion = "1.0", main = AirPlayDisplayConfig(widthPixels = 800, heightPixels = 480)),
        identity = AirPlayIdentity.generate(), pairings = PairingStore(), mfi = null,
        listener = object : AirPlaySessionListener { override fun onSessionActive(session: AirPlaySession) { activated() } },
        media = object : AirPlayMediaHandler {},
    )
}
