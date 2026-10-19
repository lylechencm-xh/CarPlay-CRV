package com.shilapi.xcertplay

import com.shilapi.xcertplay.airplay.*
import org.junit.Assert.*
import org.junit.Test

class CrvMediaOwnershipTest {
    @Test fun retiredAudioCannotStopOrDeliverIntoItsSuccessor() {
        val events = mutableListOf<String>()
        val ownership = MediaSinkOwnership(object : MediaSink {
            override fun onAudioStopped(id: AudioStreamId) { events.add("stop") }
            override fun onMicrophoneStopped(id: AudioStreamId) { events.add("mic-stop") }
            override fun onAudioRtp(id: AudioStreamId, format: AudioFormat, rtp: ByteArray, sample: Int) { events.add("rtp:$sample") }
        })
        val id = AudioStreamId(100, "media")
        val old = Any(); val next = Any()
        ownership.claimAudio(id, old)
        ownership.claimAudio(id, next)
        events.clear()
        assertFalse(ownership.releaseAudio(id, old))
        ownership.audioRtp(id, old, AudioFormat(AudioCodecKind.LPCM, 48000, 2, 100), byteArrayOf(1), 1)
        assertTrue(events.isEmpty())
        ownership.audioRtp(id, next, AudioFormat(AudioCodecKind.LPCM, 48000, 2, 100), byteArrayOf(2), 2)
        assertEquals(listOf("rtp:2"), events)
        assertTrue(ownership.releaseAudio(id, next))
        assertFalse(ownership.releaseAudio(id, next))
        assertEquals(listOf("rtp:2", "mic-stop", "stop"), events)
    }
    @Test fun retiredScreenCannotReplaceConfigOrDeactivateNewScreen() {
        val events = mutableListOf<String>()
        val ownership = MediaSinkOwnership(object : MediaSink {
            override fun onScreenStreamActive(type: Int, active: Boolean) { events.add("active:$active") }
            override fun onVideoConfig(type: Int, codecData: ByteArray) { events.add("config:${codecData[0]}") }
            override fun onVideoFrame(type: Int, naluBytes: ByteArray) { events.add("frame:${naluBytes[0]}") }
        })
        val old = Any(); val next = Any()
        ownership.claimScreen(110, old)
        val retired = ownership.screenSink(110, old)
        assertTrue(retired.isVideoVisible(110))
        ownership.claimScreen(110, next)
        assertFalse(retired.isVideoVisible(110))
        events.clear()
        retired.onVideoConfig(110, byteArrayOf(1))
        retired.onVideoFrame(110, byteArrayOf(1))
        assertFalse(ownership.releaseScreen(110, old))
        assertTrue(events.isEmpty())
        ownership.screenSink(110, next).onVideoFrame(110, byteArrayOf(2))
        assertEquals(listOf("frame:2"), events)
        assertTrue(ownership.releaseScreen(110, next))
    }
    @Test fun cleanupDoesNotUnduckAnotherSessionsAudio() {
        var unduck = 0
        val ownership = MediaSinkOwnership(object : MediaSink {
            override fun onAudioDuckingChanged(duck: Boolean) { if (!duck) unduck++ }
        })
        val id = AudioStreamId(100, "telephony"); val owner = Any()
        ownership.claimAudio(id, owner)
        ownership.resetDuckingIfIdle()
        assertEquals(0, unduck)
        ownership.releaseAudio(id, owner)
        ownership.resetDuckingIfIdle()
        assertEquals(1, unduck)
    }
}
