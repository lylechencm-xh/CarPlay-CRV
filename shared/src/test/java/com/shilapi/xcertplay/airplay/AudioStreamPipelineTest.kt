package com.shilapi.xcertplay.airplay

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
class AudioStreamPipelineTest {
    @Test fun realUdpAuthenticationDeliversOriginalReceiveTimestamp() {
        val key = ByteArray(32) { it.toByte() }
        val address = InetAddress.getByName("127.0.0.1")
        val ended = CountDownLatch(1)
        var summary = ""
        val stream = AudioStream(key, onDiagnostic = {
            if (it.startsWith("Audio receive ended")) { summary = it; ended.countDown() }
        }, receiveMaxAgeNs = 160_000_000L)
        val delivered = CountDownLatch(1)
        var arrivalTimestamp = 0L
        var callbackNs = 0L
        var payload = ByteArray(0)
        val (port, _) = stream.listen(address, object : AudioStream.Listener {
            override fun wantsPacketData() = false
            override fun onRtp(rtp: ByteArray, sample: Int, receivedNs: Long) {
                payload = rtp
                callbackNs = System.nanoTime()
                arrivalTimestamp = receivedNs
                delivered.countDown()
            }
        })
        try {
            val header = ByteArray(12).apply {
                this[0] = 0x80.toByte(); this[1] = 100; this[3] = 1; this[7] = 5
            }
            val plain = byteArrayOf(1, 2, 3, 4)
            val nonce = AirPlayCrypto.nonce64(7L)
            val sealed = AirPlayCrypto.chachaSeal(key, nonce, plain, header.copyOfRange(4, 12))
            val wire = header + sealed + nonce.copyOfRange(4, 12)
            val sentNs = System.nanoTime()
            DatagramSocket().use { it.send(DatagramPacket(wire, wire.size, address, port)) }
            assertTrue(delivered.await(5, TimeUnit.SECONDS))
            assertTrue(arrivalTimestamp >= sentNs)
            assertTrue(arrivalTimestamp <= callbackNs)
            assertArrayEquals(header + plain, payload)
        } finally { stream.close() }
        assertTrue(ended.await(5, TimeUnit.SECONDS))
        assertTrue(summary.contains("received=1 decrypted=1"))
        assertTrue(summary.contains("unaccounted=0"))
        assertTrue(summary.contains("streamId="))
    }

    @Test fun closeWaitsForBothWorkersBeforePublishingFinalAccounting() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val ended = CountDownLatch(1)
        var summary = ""
        val key = ByteArray(32)
        val stream = AudioStream(key, onDiagnostic = {
            if (it.startsWith("Audio receive ended")) { summary = it; ended.countDown() }
        })
        val address = InetAddress.getByName("127.0.0.1")
        val (port, _) = stream.listen(address, object : AudioStream.Listener {
            override fun wantsPacketData() = false
            override fun onRtp(rtp: ByteArray, sample: Int, receivedNs: Long) {
                entered.countDown()
                var interrupted = false
                while (true) {
                    try { release.await(); break }
                    catch (_: InterruptedException) { interrupted = true }
                }
                if (interrupted) Thread.currentThread().interrupt()
            }
        })
        try {
            val header = ByteArray(12)
            val nonce = AirPlayCrypto.nonce64(1)
            val wire = header + AirPlayCrypto.chachaSeal(key, nonce, byteArrayOf(1), header.copyOfRange(4, 12)) +
                nonce.copyOfRange(4, 12)
            DatagramSocket().use { it.send(DatagramPacket(wire, wire.size, address, port)) }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            stream.close()
            assertFalse(ended.await(100, TimeUnit.MILLISECONDS))
            release.countDown()
            assertTrue(ended.await(5, TimeUnit.SECONDS))
            assertTrue(summary.contains("unaccounted=0"))
        } finally { release.countDown(); stream.close() }
    }
}
