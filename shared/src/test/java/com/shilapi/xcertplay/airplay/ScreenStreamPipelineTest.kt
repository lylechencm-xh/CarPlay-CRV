package com.shilapi.xcertplay.airplay

import java.net.InetAddress
import java.net.Socket
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Real TCP + AEAD regression: the asynchronous boundary must preserve config and nonce order. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
class ScreenStreamPipelineTest {
    private val key = ByteArray(32) { it.toByte() }
    private val address = InetAddress.getByName("127.0.0.1")

    @Test fun tcpFramesAndConfigurationRemainOrderedAndEofDrainsPendingWork() {
        val events = Collections.synchronizedList(mutableListOf<String>())
        val ended = CountDownLatch(1)
        val stream = ScreenStream(key)
        val port = stream.listen(address, object : ScreenStream.Listener {
            override fun wantsLengthPrefixedFrames() = true
            override fun onFrame(naluBytes: ByteArray, arrivalNs: Long) {
                assertTrue(arrivalNs > 0L)
                events.add("frame:${naluBytes.last()}")
            }
            override fun onConfig(codecData: ByteArray) { events.add("config:${codecData.last()}") }
            override fun onClosed(cause: Throwable?) { events.add(if (cause == null) "end" else "failure:$cause"); ended.countDown() }
        })
        try {
            Socket(address, port).use { socket ->
                writeFrame(socket, 0L, 1)
                val config = byteArrayOf(0, 0, 0, 0, 97, 118, 99, 67, 1, 2, 3)
                val header = header(config.size, 1)
                socket.getOutputStream().write(header)
                socket.getOutputStream().write(config)
                writeFrame(socket, 1L, 2)
                socket.shutdownOutput()
                assertTrue(ended.await(5, TimeUnit.SECONDS))
            }
            assertEquals(listOf("frame:1", "config:3", "frame:2", "end"), events)
        } finally { stream.close() }
    }

    @Test fun explicitlyRetiredStreamDoesNotSignalSessionFailure() {
        val ended = CountDownLatch(1)
        val stream = ScreenStream(key)
        val port = stream.listen(address, object : ScreenStream.Listener {
            override fun onClosed(cause: Throwable?) { ended.countDown() }
        })
        Socket(address, port).use {
            stream.close()
            assertFalse(ended.await(100, TimeUnit.MILLISECONDS))
        }
    }

    private fun writeFrame(socket: Socket, nonce: Long, value: Int) {
        val payload = byteArrayOf(0, 0, 0, 2, 0x65, value.toByte())
        val header = header(payload.size + 16, 0)
        val encrypted = AirPlayCrypto.chachaSeal(key, AirPlayCrypto.nonce64(nonce), payload, header)
        socket.getOutputStream().write(header)
        socket.getOutputStream().write(encrypted)
    }
    private fun header(length: Int, opcode: Int): ByteArray = ByteArray(128).apply {
        for (i in 0..3) this[i] = (length ushr (i * 8)).toByte()
        this[4] = opcode.toByte()
    }
}
