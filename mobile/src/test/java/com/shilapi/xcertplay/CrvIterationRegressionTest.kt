package com.shilapi.xcertplay

import com.shilapi.xcertplay.airplay.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class CrvIterationRegressionTest {
    @Test fun profileIsExplicitAndInvalidPreferenceFallsBackToOriginalCanvas() {
        assertEquals(CrvVideoProfile.STANDARD, CrvVideoProfile.fromStored(null))
        assertEquals(CrvVideoProfile.STANDARD, CrvVideoProfile.fromStored("broken"))
        assertEquals(1280, CrvVideoProfile.fromStored(null).width)
        assertEquals(960, CrvVideoProfile.fromStored("LOW_LOAD").width)
        assertEquals(540, CrvVideoProfile.LOW_LOAD.height)
    }

    @Test fun ordinaryTraceGateRetainsMilestonesAndFaultsWithoutFormattingTraffic() {
        val lines = mutableListOf<String>()
        val trace = CrvProtocolTraceRecorder(lines::add, detailed = { false })
        trace.record(CrvProtocolLayer.NCM, CrvProtocolDirection.DEVICE_TO_HOST, "traffic", 100)
        assertTrue(lines.isEmpty())
        trace.signal(CrvProtocolLayer.CARPLAY_SESSION, "active")
        trace.fault(CrvProtocolLayer.USB, "detached", "active device absent")
        assertEquals(2, lines.size)
        assertEquals(CrvLogCategory.PROTOCOL, CrvLogCategory.classify("attempt=7 " + lines.first()))
        assertEquals(CrvLogCategory.VIDEO, CrvLogCategory.classify("[CARPLAY_ACTIVE] attempt=7 Video: pipeline stats"))
    }

    @Test fun cameraAndDecoderResetsKeepLivePlaybackPageUntilExplicitSessionEnd() {
        val ui = CrvConnectionPresentation()
        ui.accept(CrvConnectionEvent.ACTIVE, 1L)
        assertFalse(ui.retainsVideoPage())
        ui.accept(CrvConnectionEvent.VIDEO_RENDERED, 2L)
        repeat(100) {
            ui.accept(CrvConnectionEvent.VIDEO_RESET, 3L + it)
            assertFalse(ui.rendered)
            assertTrue(ui.retainsVideoPage())
            ui.accept(CrvConnectionEvent.ACTIVE, 4L + it)
            assertTrue(ui.retainsVideoPage())
        }
        ui.accept(CrvConnectionEvent.SESSION_ENDED, 200L)
        assertFalse(ui.retainsVideoPage())
        ui.clear()
        ui.accept(CrvConnectionEvent.ACTIVE, 201L)
        assertFalse(ui.retainsVideoPage())
    }

    @Test fun timestampAndDiscontinuityCallbacksCannotReachReplacementScreen() {
        var arrivals = 0L
        var resets = 0
        val ownership = MediaSinkOwnership(object : MediaSink {
            override fun onVideoFrame(type: Int, naluBytes: ByteArray, arrivalNs: Long) { arrivals = arrivalNs }
            override fun onVideoDiscontinuity(type: Int) { resets++ }
        })
        val old = Any(); val next = Any()
        ownership.claimScreen(110, old)
        val retired = ownership.screenSink(110, old)
        ownership.claimScreen(110, next)
        retired.onVideoFrame(110, byteArrayOf(1), 99L)
        retired.onVideoDiscontinuity(110)
        assertEquals(0L, arrivals)
        assertEquals(0, resets)
        val current = ownership.screenSink(110, next)
        current.onVideoFrame(110, byteArrayOf(1), 123L)
        current.onVideoDiscontinuity(110)
        assertEquals(123L, arrivals)
        assertEquals(1, resets)
    }

    @Test fun modeIsRestoredOnlyAfterBothMicrophoneAndOutputRelease() {
        var mode = 0
        val lease = CrvCommunicationModeLease({ mode }, { mode = it }, { false })
        lease.acquire("output"); lease.acquire("input")
        assertEquals(3, mode)
        lease.release("output")
        assertEquals(3, mode)
        lease.release("input")
        assertEquals(0, mode)
    }

    @Test fun foreignCallAndScoAreNeverOverriddenOnAcquireOrRestore() {
        var mode = 2
        var sco = false
        val lease = CrvCommunicationModeLease({ mode }, { mode = it }, { sco })
        lease.acquire("oem"); lease.close()
        assertEquals(2, mode)
        mode = 0; sco = true
        lease.acquire("sco"); lease.close()
        assertEquals(0, mode)
        sco = false
        lease.acquire("carplay")
        mode = 2
        lease.close()
        assertEquals(2, mode)
    }

    @Test fun promptBufferDoesNotInheritMusicByteFloorButKeepsHardwareMinimum() {
        assertEquals(2880, CrvAudioBufferPolicy.preferred(2048, 32000, false))
        assertEquals(4096, CrvAudioBufferPolicy.preferred(4096, 32000, false))
        assertEquals(42240, CrvAudioBufferPolicy.preferred(4096, 192000, true))
    }

    @Test fun diagnosticQueuePreservesErrorsAndDrainsBeforeClosing() {
        val queue = CrvDiagnosticQueue(2)
        fun entry(text: String, important: Boolean) = CrvDiagnosticQueue.Entry(text, 0L, 0L, important)
        queue.offer(entry("routine", false)); queue.offer(entry("connected", true))
        queue.offer(entry("failure", true))
        assertEquals(1L, queue.dropped())
        queue.close()
        assertEquals("connected", queue.take()?.message)
        assertEquals("failure", queue.take()?.message)
        assertNull(queue.take())
        queue.offer(entry("late", true))
        assertNull(queue.take())
    }

    @Test fun diagnosticCloseWakesIdleWriter() {
        val queue = CrvDiagnosticQueue()
        val entered = CountDownLatch(1); val done = CountDownLatch(1)
        Thread { entered.countDown(); assertNull(queue.take()); done.countDown() }.apply { isDaemon = true; start() }
        assertTrue(entered.await(1, TimeUnit.SECONDS))
        queue.close()
        assertTrue(done.await(1, TimeUnit.SECONDS))
    }
}
