package com.shilapi.xcertplay

import com.shilapi.xcertplay.airplay.VideoCodec
import com.shilapi.xcertplay.airplay.AudioReceiveQueue
import com.shilapi.xcertplay.airplay.AirPlayConfig
import com.shilapi.xcertplay.airplay.AirPlayDisplayConfig
import com.shilapi.xcertplay.airplay.AirPlayInfoPlist
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class CrvConnectionRegressionTest {
    @Test fun protocolActiveWaitsForActualOutputAndLateFrameCannotReviveEndedSession() {
        val ui = CrvConnectionPresentation()
        ui.accept(CrvConnectionEvent.ACTIVE, 0L)
        assertFalse(ui.shouldShowVideo())
        ui.accept(CrvConnectionEvent.VIDEO_RENDERED, 5_000L)
        assertTrue(ui.shouldShowVideo())
        ui.accept(CrvConnectionEvent.SESSION_ENDED, 6_000L)
        ui.accept(CrvConnectionEvent.VIDEO_RENDERED, 6_001L)
        assertFalse(ui.shouldShowVideo())
    }

    @Test fun firstFrameCanRaceWithActiveButFailureAlwaysReturnsToConnectionState() {
        val ui = CrvConnectionPresentation()
        ui.accept(CrvConnectionEvent.VIDEO_RENDERED, 0L)
        assertFalse(ui.shouldShowVideo())
        ui.accept(CrvConnectionEvent.ACTIVE, 1L)
        assertTrue(ui.shouldShowVideo())
        ui.accept(CrvConnectionEvent.FAILED, 2L)
        assertFalse(ui.shouldShowVideo())
        ui.accept(CrvConnectionEvent.ACTIVE, 3L)
        assertFalse(ui.shouldShowVideo())
    }

    @Test fun surfaceReplacementRequiresANewFrameEvenWhenSessionRemainsActive() {
        val ui = CrvConnectionPresentation()
        ui.accept(CrvConnectionEvent.ACTIVE, 0L)
        ui.accept(CrvConnectionEvent.VIDEO_RENDERED, 1L)
        assertTrue(ui.shouldShowVideo())
        ui.accept(CrvConnectionEvent.VIDEO_RESET, 10L)
        assertTrue(ui.active)
        assertFalse(ui.shouldShowVideo())
        assertFalse(ui.stable(100_000L))
        ui.accept(CrvConnectionEvent.VIDEO_RENDERED, 100_001L)
        assertTrue(ui.shouldShowVideo())
        assertFalse(ui.stable(100_002L))
    }

    @Test fun legacyVideoLimitActuallyReachesInfoWithoutChangingOrdinaryDisplaySettings() {
        fun maximum(display: AirPlayDisplayConfig): Int {
            val config = AirPlayConfig("test", "device", "bt", "1", display)
            val entries = AirPlayInfoPlist.build(config)["displays"] as List<*>
            return (entries.first() as Map<*, *>)["maxFPS"] as Int
        }
        val normal = AirPlayDisplayConfig(1280, 720, fps = 30)
        assertEquals(30, maximum(normal))
        assertEquals(20, maximum(normal.copy(streamFpsLimit = Crv2021Config.CARPLAY_MAX_FPS)))
        assertEquals(30, maximum(normal.copy(streamFpsLimit = 60)))
    }

    @Test fun activeWithoutPlaybackOrLongStallCannotReplenishRetryBudget() {
        val ui = CrvConnectionPresentation()
        ui.accept(CrvConnectionEvent.ACTIVE, 0L)
        assertFalse(ui.stable(100_000L))
        ui.accept(CrvConnectionEvent.VIDEO_RENDERED, 5_000L)
        assertFalse(ui.stable(65_000L))
        ui.accept(CrvConnectionEvent.VIDEO_RENDERED, 65_000L)
        assertTrue(ui.stable(65_000L))
        ui.clear()
        assertFalse(ui.stable(100_000L))
    }

    @Test fun allAutomaticEntryPointsShareTheSameHardBudgetUntilExplicitReset() {
        val budget = CrvConnectionAttemptBudget()
        // Initial connect, stopped callback, attach broadcast, Surface and another retry.
        repeat(5) { assertTrue(budget.tryStart()) }
        repeat(100) { assertFalse(budget.tryStart()) }
        assertEquals(5, budget.attempts)
        budget.reset()
        assertTrue(budget.tryStart())
        assertEquals(1, budget.attempts)
    }

    @Test fun vpnNullEstablishmentWaitsForAuthorizationInsteadOfTransientRetry() {
        val failure = classifyCrvReconnectFailure(IllegalStateException("NCM/AirPlay attach failed",
            java.io.IOException("VpnService.establish returned null")))
        assertEquals(CrvReconnectFailure.PERMISSION_REQUIRED, failure)
        assertNull(crvReconnectDecision(failure, 0).delayMillis)
    }

    @Test fun probeAndReplacedTcpSessionsCannotEndTheOwningCarPlaySession() {
        val owner = Any()
        assertFalse(ownsCrvSessionEnd(null, Any()))
        assertFalse(ownsCrvSessionEnd(owner, Any()))
        assertTrue(ownsCrvSessionEnd(owner, owner))
    }

    @Test fun equalVideoConfigPreservesDecoderButChangedCodecOrLostDecoderStillConfigures() {
        val config = byteArrayOf(1, 2, 3)
        assertFalse(crvNeedsVideoConfiguration(true, VideoCodec.H264, config, VideoCodec.H264, config.copyOf()))
        assertTrue(crvNeedsVideoConfiguration(false, VideoCodec.H264, config, VideoCodec.H264, config.copyOf()))
        assertTrue(crvNeedsVideoConfiguration(true, VideoCodec.H264, config, VideoCodec.H265, config.copyOf()))
        assertTrue(crvNeedsVideoConfiguration(true, VideoCodec.H264, config, VideoCodec.H264, byteArrayOf(1, 2, 4)))
    }

    @Test fun duplicateConfigCannotClearAFullQueueOfReferenceFrames() {
        val queue = CrvVideoJobQueue(3, 100)
        val config = byteArrayOf(1, 2)
        val frame = byteArrayOf(9)
        queue.offerConfig(VideoCodec.H264, config)
        queue.offerFrame(frame, 1L)
        queue.offerFrame(frame, 2L)
        queue.offerConfig(VideoCodec.H264, config.copyOf())
        val job = CrvVideoJobQueue.Holder()
        assertTrue(queue.poll(0, job))
        assertEquals(CrvVideoJobQueue.TYPE_CONFIG, job.type)
        assertTrue(queue.poll(0, job))
        assertEquals(CrvVideoJobQueue.TYPE_FRAME, job.type)
        assertEquals(1L, job.receivedNs)
        assertTrue(queue.poll(0, job))
        assertEquals(2L, job.receivedNs)
    }

    private fun rtp(payload: Int) = ByteArray(12 + payload).also { it[0] = 0x80.toByte() }

    @Test fun pcmBudgetUsesDurationAndExpiresEvenWithoutFurtherIncomingPackets() {
        val queue = CrvAudioPacketQueue(96, maxAgeNs = 200L, maxPayloadBytes = 12L)
        queue.offer(rtp(8), 1, 0L)
        queue.offer(rtp(8), 2, 1L)
        assertEquals(8L, queue.queuedPayloadBytes())
        assertEquals(1L, queue.stats(1L).dropped)
        val packet = CrvAudioPacketQueue.MutablePacket()
        assertFalse(queue.poll(0, packet) { 202L })
        assertEquals(0L, queue.queuedPayloadBytes())
        assertEquals(1L, queue.stats(202L).expired)
        queue.offer(rtp(13), 3, 203L)
        assertEquals(0, queue.size())
    }

    @Test fun slowAudioConsumerCannotBlockReaderAndOnlyFreshOrderedPacketsSurvive() {
        val queue = AudioReceiveQueue(2, 100L)
        queue.offer(byteArrayOf(1), 0L)
        queue.offer(byteArrayOf(2), 1L)
        queue.offer(byteArrayOf(3), 2L)
        assertEquals(1L, queue.stats().overflow)
        val packet = AudioReceiveQueue.Packet()
        assertTrue(queue.poll(packet) { 2L })
        assertArrayEquals(byteArrayOf(2), packet.bytes)
        queue.offer(byteArrayOf(4), 103L)
        assertEquals(1L, queue.stats().expired)
        assertTrue(queue.poll(packet) { 103L })
        assertArrayEquals(byteArrayOf(4), packet.bytes)
        queue.close()
        assertFalse(queue.poll(packet))
    }

    @Test fun closingAudioQueueUnblocksIdleWorkerAndRejectsLatePackets() {
        val queue = AudioReceiveQueue()
        val entered = CountDownLatch(1)
        val done = CountDownLatch(1)
        val thread = Thread {
            entered.countDown()
            assertFalse(queue.poll(AudioReceiveQueue.Packet()))
            done.countDown()
        }
        thread.isDaemon = true
        thread.start()
        assertTrue(entered.await(1, TimeUnit.SECONDS))
        queue.close()
        assertTrue(done.await(1, TimeUnit.SECONDS))
        queue.offer(byteArrayOf(1))
        assertEquals(0, queue.stats().depth)
    }
}
