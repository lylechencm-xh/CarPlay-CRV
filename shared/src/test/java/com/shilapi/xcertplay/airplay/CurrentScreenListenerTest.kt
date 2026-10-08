package com.shilapi.xcertplay.airplay

import java.io.Closeable
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Backports DiPlay's current-screen callback isolation to CR-V API17 without
 * enabling buffered audio, frame pacing, or newer Android USB APIs.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
class CurrentScreenListenerTest {
    private sealed class Event {
        data class Codec(val type: Int, val codec: VideoCodec) : Event()
        data class Config(val type: Int, val data: List<Byte>) : Event()
        data class Frame(val type: Int, val data: List<Byte>) : Event()
    }

    private class RecordingSink : MediaSink {
        val events = mutableListOf<Event>()
        val activity = mutableListOf<Pair<Int, Boolean>>()
        override fun onVideoCodec(type: Int, codec: VideoCodec) {
            events += Event.Codec(type, codec)
        }
        override fun onVideoConfig(type: Int, codecData: ByteArray) {
            events += Event.Config(type, codecData.toList())
        }
        override fun onVideoFrame(type: Int, naluBytes: ByteArray) {
            events += Event.Frame(type, naluBytes.toList())
        }
        override fun onScreenStreamActive(type: Int, active: Boolean) {
            activity += type to active
        }
        fun take(): List<Event> = events.toList().also { events.clear() }
    }

    private val config = byteArrayOf(1, 2, 3)
    private val frame = byteArrayOf(4, 5, 6)

    private fun ScreenStream.Listener.deliverAll(codec: VideoCodec = VideoCodec.H264) {
        onCodec(codec)
        onConfig(config)
        onFrame(frame)
    }

    private fun expected(type: Int, codec: VideoCodec = VideoCodec.H264) = listOf(
        Event.Codec(type, codec),
        Event.Config(type, config.toList()),
        Event.Frame(type, frame.toList()),
    )

    @Test fun callbacksAreForwardedOnlyWhileCurrent() {
        val sink = RecordingSink()
        val current = AtomicBoolean(true)
        var checks = 0
        val listener = currentScreenListener(110, sink) { checks++; current.get() }

        listener.deliverAll()
        assertEquals(expected(110), sink.take())
        assertEquals(3, checks)

        current.set(false)
        listener.deliverAll(VideoCodec.H265)
        assertTrue(sink.take().isEmpty())
        assertEquals(6, checks)
    }

    @Test fun replacementBetweenCodecAndFrameDropsOnlyLateCallbacks() {
        val sink = RecordingSink()
        val current = AtomicBoolean(true)
        val listener = currentScreenListener(110, sink) { current.get() }

        listener.onCodec(VideoCodec.H264)
        current.set(false)
        listener.onConfig(config)
        listener.onFrame(frame)
        assertEquals(listOf(Event.Codec(110, VideoCodec.H264)), sink.take())
    }

    @Test fun oldVideoStreamCannotPoisonReplacementOrCloseItsSession() {
        val sink = RecordingSink()
        val engine = CarPlayMediaEngine(sink)
        val session = testSession()
        try {
            PairVerify::class.java.getDeclaredField("sharedSecret").apply { isAccessible = true }
                .set(session.pairVerify, ByteArray(32) { it.toByte() })
            assertNotNull(session.sharedSecret)
            val streams = streamsOf(engine)
            val main = CarPlayMediaEngine.StreamKey(session, 110)
            val cluster = CarPlayMediaEngine.StreamKey(session, 111)

            assertNotNull(engine.onScreen(session, 110, mapOf("streamConnectionID" to 1L)))
            val first = streams[main] as ScreenStream
            val firstListener = listenerOf(first)
            firstListener.deliverAll()
            assertEquals(expected(110), sink.take())

            assertNotNull(engine.onScreen(session, 111, mapOf("streamConnectionID" to 3L)))
            val clusterListener = listenerOf(streams[cluster] as ScreenStream)

            assertNotNull(engine.onScreen(session, 110, mapOf("streamConnectionID" to 2L)))
            val second = streams[main] as ScreenStream
            assertNotSame(first, second)
            val secondListener = listenerOf(second)

            firstListener.deliverAll(VideoCodec.H265)
            assertTrue(sink.take().isEmpty())
            firstListener.onClosed(null)
            assertFalse("replaced video must not close active CarPlay session", session.isClosed)
            assertTrue("replacement must remain registered", streams[main] === second)

            secondListener.deliverAll()
            assertEquals(expected(110), sink.take())
            clusterListener.deliverAll(VideoCodec.H265)
            assertEquals(expected(111, VideoCodec.H265), sink.take())
        } finally {
            engine.onSessionClosed(session)
            session.close()
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun streamsOf(engine: CarPlayMediaEngine): Map<CarPlayMediaEngine.StreamKey, Closeable> =
        CarPlayMediaEngine::class.java.getDeclaredField("streams").apply { isAccessible = true }
            .get(engine) as Map<CarPlayMediaEngine.StreamKey, Closeable>

    private fun listenerOf(stream: ScreenStream): ScreenStream.Listener =
        ScreenStream::class.java.getDeclaredField("listener").apply { isAccessible = true }
            .get(stream) as ScreenStream.Listener

    private fun testSession() = AirPlaySession(
        socket = Socket(),
        config = AirPlayConfig(
            deviceName = "CR-V test",
            deviceId = "02:00:00:00:00:02",
            btMac = "02:00:00:00:00:01",
            sourceVersion = "1.0",
            main = AirPlayDisplayConfig(widthPixels = 800, heightPixels = 480),
        ),
        identity = AirPlayIdentity.generate(),
        pairings = PairingStore(),
        mfi = null,
        listener = object : AirPlaySessionListener {},
        media = object : AirPlayMediaHandler {},
    )
}
