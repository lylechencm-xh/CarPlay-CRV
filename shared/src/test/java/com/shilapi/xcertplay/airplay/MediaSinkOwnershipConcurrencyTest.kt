package com.shilapi.xcertplay.airplay

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.*
import org.junit.Test

class MediaSinkOwnershipConcurrencyTest {
    @Test fun blockedMusicCleanupDoesNotBlockVideoOrGuidanceAndCannotRetireSuccessor() {
        val music = AudioStreamId(100, "media")
        val guidance = AudioStreamId(100, "guidance")
        val cleanupEntered = CountDownLatch(1)
        val resumeCleanup = CountDownLatch(1)
        val block = AtomicBoolean(false)
        val sink = object : MediaSink {
            override fun onAudioStopped(id: AudioStreamId) {
                if (id == music && block.get()) {
                    cleanupEntered.countDown()
                    check(resumeCleanup.await(5, TimeUnit.SECONDS))
                }
            }
        }
        val owners = MediaSinkOwnership(sink)
        val old = Any(); val next = Any(); val prompt = Any(); val screen = Any()
        owners.claimAudio(music, old)
        owners.claimAudio(guidance, prompt)
        owners.claimScreen(110, screen)
        val workers = Executors.newFixedThreadPool(3)
        try {
            block.set(true)
            val retiring = workers.submit<Boolean> { owners.releaseAudio(music, old) }
            assertTrue(cleanupEntered.await(2, TimeUnit.SECONDS))
            val independent = workers.submit<Boolean> {
                owners.screenSink(110, screen).onVideoFrame(110, byteArrayOf(1))
                var delivered = false
                owners.deliverAudio(guidance, prompt) { delivered = true }
                delivered
            }
            assertTrue(independent.get(2, TimeUnit.SECONDS))
            val replacement = workers.submit { owners.claimAudio(music, next) }
            resumeCleanup.countDown()
            assertTrue(retiring.get(2, TimeUnit.SECONDS))
            replacement.get(2, TimeUnit.SECONDS)
            assertFalse(owners.releaseAudio(music, old))
            var delivered = false
            owners.deliverAudio(music, next) { delivered = true }
            assertTrue(delivered)
        } finally {
            resumeCleanup.countDown()
            workers.shutdownNow()
        }
    }

    @Test fun closedReceiveQueueCountsCleanupSeparatelyAndOnlyOnce() {
        val queue = AudioReceiveQueue(maxAgeNs = 100L)
        queue.offer(byteArrayOf(1), 0L)
        queue.offer(byteArrayOf(2), 200L) // First packet expires.
        queue.offer(byteArrayOf(3), 210L)
        queue.close()
        queue.close()
        assertEquals(1L, queue.stats().expired)
        assertEquals(2L, queue.stats().cleanup)
        assertEquals(0, queue.stats().depth)
        assertEquals(0L, queue.stats().overflow)
    }
}
