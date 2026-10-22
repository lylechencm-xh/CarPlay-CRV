package com.shilapi.xcertplay

import com.shilapi.xcertplay.airplay.*
import org.junit.Assert.*
import org.junit.Test

class CrvMediaLatencyRegressionTest {
    @Test fun musicSurvivesReceiveJitterWithoutRestartingItsQueueDeadline() {
        val arrived = 1_000_000_000L
        val receive = AudioReceiveQueue(maxAgeNs = CrvAudioLatencyBudget.receiveNs(true))
        val packet = AudioReceiveQueue.Packet()
        val bytes = ByteArray(24).apply { this[0] = 0x80.toByte() }
        receive.offer(bytes, arrived)
        // This packet used to expire in the fixed 80ms pre-authentication queue.
        assertTrue(receive.poll(packet) { arrived + 120_000_000L })
        val render = CrvAudioPacketQueue(8, maxAgeNs = CrvAudioLatencyBudget.totalQueueNs(true))
        render.offer(packet.bytes!!, 1, arrived + 140_000_000L, packet.receivedNs)
        assertEquals(140_000_000L, render.stats(arrived + 140_000_000L).oldestAgeNs)
        val output = CrvAudioPacketQueue.MutablePacket()
        // Its deadline is still arrival+200ms, rather than callback+200ms.
        assertFalse(render.poll(0L, output) { arrived + 201_000_000L })
        assertEquals(1L, render.stats().expired)
    }

    @Test fun excessivelyDelayedCallbackCannotMakeAnOldPacketFresh() {
        val queue = CrvAudioPacketQueue(8, maxAgeNs = CrvAudioLatencyBudget.totalQueueNs(true))
        queue.offer(ByteArray(16), 1, 1_250_000_000L, 1_000_000_000L)
        assertEquals(0, queue.size())
        assertEquals(1L, queue.stats().expired)
        assertEquals(1L, queue.stats().dropped)
        assertEquals(80_000_000L, CrvAudioLatencyBudget.receiveNs(false))
        assertEquals(100_000_000L, CrvAudioLatencyBudget.totalQueueNs(false))
    }

    @Test fun audioTimestampSurvivesOwnershipDeliveryAndRetiredOwnerIsRejected() {
        val timestamps = mutableListOf<Long>()
        val ownership = MediaSinkOwnership(object : MediaSink {
            override fun onAudioRtp(id: AudioStreamId, format: AudioFormat, rtp: ByteArray,
                                    sample: Int, receivedNs: Long) { timestamps += receivedNs }
        })
        val id = AudioStreamId(100, "media")
        val format = AudioFormat(AudioCodecKind.LPCM, 48000, 2, 100)
        val old = Any(); val current = Any()
        ownership.claimAudio(id, old)
        ownership.claimAudio(id, current)
        ownership.audioRtp(id, old, format, byteArrayOf(1), 1, 123L)
        ownership.audioRtp(id, current, format, byteArrayOf(2), 2, 456L)
        assertEquals(listOf(456L), timestamps)
    }

    @Test fun repeatedRecoveryGivesKeyframeTimeToProduceFirstOutput() {
        val gate = CrvVideoRecoveryGate()
        assertTrue(gate.allow(1_000_000_000L, false))
        assertFalse(gate.allow(2_200_000_000L, true))
        assertFalse(gate.allow(3_400_000_000L, true))
        assertFalse(gate.allow(4_999_000_000L, true))
        // A genuinely failed startup still gets another recovery attempt.
        assertTrue(gate.allow(5_000_000_000L, true))
        // Once a keyframe was accepted, a newly broken reference chain must reset now.
        assertTrue(gate.allow(5_100_000_000L, false))
        assertFalse(gate.allow(8_000_000_000L, true))
        assertTrue(gate.allow(9_100_000_000L, true))
        assertEquals(4L, gate.suppressedTotal)
    }
}
