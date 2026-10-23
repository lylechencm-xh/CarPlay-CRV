package com.shilapi.xcertplay.airplay

import java.net.InetAddress
import java.net.Socket
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
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

    @Test fun hiddenCiphertextIsNotOpenedButNonceAndConfigurationSurviveResume() {
        val visible = AtomicBoolean(false)
        val configured = CountDownLatch(1)
        val ended = CountDownLatch(1)
        val frames = Collections.synchronizedList(mutableListOf<Int>())
        var failure: Throwable? = null
        val stream = ScreenStream(key)
        val port = stream.listen(address, object : ScreenStream.Listener {
            override fun isVideoVisible() = visible.get()
            override fun wantsLengthPrefixedFrames() = true
            override fun onConfig(codecData: ByteArray) { configured.countDown() }
            override fun onFrame(naluBytes: ByteArray, arrivalNs: Long) { frames.add(naluBytes.last().toInt()) }
            override fun onClosed(cause: Throwable?) { failure = cause; ended.countDown() }
        })
        try {
            Socket(address, port).use { socket ->
                // Invalid tag would fail the stream if hidden images still entered AEAD.
                socket.getOutputStream().write(header(22, 0))
                socket.getOutputStream().write(ByteArray(22))
                val config = byteArrayOf(0, 0, 0, 0, 97, 118, 99, 67, 1, 2, 3)
                socket.getOutputStream().write(header(config.size, 1))
                socket.getOutputStream().write(config)
                assertTrue(configured.await(5, TimeUnit.SECONDS))
                visible.set(true)
                stream.refreshVideoPriority()
                writeFrame(socket, 1L, 2)
                socket.shutdownOutput()
                assertTrue(ended.await(5, TimeUnit.SECONDS))
            }
            assertNull(failure)
            assertEquals(listOf(2), frames)
        } finally { stream.close() }
    }

    @Test fun cameraRoundTripInvalidatesOldQueuedFrameWithoutDroppingNewVisibleChain() {
        val visible = AtomicBoolean(true)
        val blocked = CountDownLatch(1)
        val resume = CountDownLatch(1)
        val ended = CountDownLatch(1)
        val frames = Collections.synchronizedList(mutableListOf<Int>())
        var failure: Throwable? = null
        val stream = ScreenStream(key)
        val port = stream.listen(address, object : ScreenStream.Listener {
            override fun isVideoVisible() = visible.get()
            override fun wantsLengthPrefixedFrames() = true
            override fun onFrame(naluBytes: ByteArray, arrivalNs: Long) {
                frames.add(naluBytes.last().toInt())
                if (frames.size == 1) { blocked.countDown(); check(resume.await(5, TimeUnit.SECONDS)) }
            }
            override fun onClosed(cause: Throwable?) { failure = cause; ended.countDown() }
        })
        try {
            Socket(address, port).use { socket ->
                writeFrame(socket, 0L, 1)
                assertTrue(blocked.await(5, TimeUnit.SECONDS))
                writeFrame(socket, 1L, 2)
                val queue = ScreenStream::class.java.getDeclaredField("receiveQueue").apply {
                    isAccessible = true
                }.get(stream) as ScreenReceiveQueue
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
                while (!queue.hasNewer(0L) && System.nanoTime() < deadline) Thread.sleep(5)
                assertTrue(queue.hasNewer(0L))
                visible.set(false); stream.refreshVideoPriority()
                visible.set(true); stream.refreshVideoPriority()
                resume.countDown()
                writeFrame(socket, 2L, 3)
                socket.shutdownOutput()
                assertTrue(ended.await(5, TimeUnit.SECONDS))
            }
            assertNull(failure)
            assertEquals(listOf(1, 3), frames)
        } finally { resume.countDown(); stream.close() }
    }

    @Test fun visibleInvalidTagStillFailsClosed() {
        val ended = CountDownLatch(1)
        var failure: Throwable? = null
        val stream = ScreenStream(key)
        val port = stream.listen(address, object : ScreenStream.Listener {
            override fun onFrame(naluBytes: ByteArray) { fail("Unauthenticated image delivered") }
            override fun onClosed(cause: Throwable?) { failure = cause; ended.countDown() }
        })
        try {
            Socket(address, port).use { socket ->
                socket.getOutputStream().write(header(22, 0))
                socket.getOutputStream().write(ByteArray(22))
                assertTrue(ended.await(5, TimeUnit.SECONDS))
            }
            assertNotNull(failure)
        } finally { stream.close() }
    }

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

    @Test fun authenticationBacklogPreservesInterframesInsteadOfRepeatedDiscontinuities() {
        val firstCallback = CountDownLatch(1)
        val resume = CountDownLatch(1)
        val ended = CountDownLatch(1)
        val frames = Collections.synchronizedList(mutableListOf<Int>())
        var discontinuities = 0
        var failure: Throwable? = null
        val stream = ScreenStream(key)
        val port = stream.listen(address, object : ScreenStream.Listener {
            override fun wantsLengthPrefixedFrames() = true
            override fun onFrame(naluBytes: ByteArray, arrivalNs: Long) {
                frames.add(naluBytes.last().toInt())
                if (frames.size == 1) {
                    firstCallback.countDown()
                    check(resume.await(5, TimeUnit.SECONDS))
                }
            }
            override fun onDiscontinuity() { discontinuities++ }
            override fun onClosed(cause: Throwable?) { failure = cause; ended.countDown() }
        })
        try {
            Socket(address, port).use { socket ->
                writeFrame(socket, 0L, 1)
                assertTrue(firstCallback.await(5, TimeUnit.SECONDS))
                writeFrame(socket, 1L, 2, 0x41)
                writeFrame(socket, 2L, 3)
                writeFrame(socket, 3L, 4, 0x41)
                val queue = ScreenStream::class.java.getDeclaredField("receiveQueue").apply {
                    isAccessible = true
                }.get(stream) as ScreenReceiveQueue
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
                while (!queue.hasNewer(0L) && System.nanoTime() < deadline) Thread.sleep(5)
                assertTrue(queue.hasNewer(0L))
                // Simulate a processing pause after TCP receive, beyond the old 250ms limit.
                Thread.sleep(350)
                resume.countDown()
                socket.shutdownOutput()
                assertTrue(ended.await(5, TimeUnit.SECONDS))
            }
            assertNull(failure)
            assertEquals(listOf(1, 2, 3, 4), frames)
            assertEquals(0, discontinuities)
        } finally {
            resume.countDown()
            stream.close()
        }
    }

    private fun writeFrame(socket: Socket, nonce: Long, value: Int, nalType: Int = 0x65) {
        val payload = byteArrayOf(0, 0, 0, 2, nalType.toByte(), value.toByte())
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
