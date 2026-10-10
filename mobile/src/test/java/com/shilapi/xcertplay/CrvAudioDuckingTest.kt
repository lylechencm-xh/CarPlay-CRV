package com.shilapi.xcertplay

import org.junit.Assert.*
import org.junit.Test

class CrvAudioDuckingTest {
    private var clock = 0L
    private val ducking = CrvAudioDucking { clock }
    private val voice = byteArrayOf(0, 4) // little-endian PCM16, amplitude 1024

    @Test fun silenceAndMalformedFramesDoNotSuppressMusic() {
        ducking.voicePcm(ByteArray(128), 0, 128)
        ducking.voicePcm(voice, -1, 2)
        ducking.voicePcm(voice, 0, 3)
        assertEquals(1f, ducking.mediaGain(), 0f)
    }

    @Test fun speechLowersMusicAndSilentPersistentStreamCannotKeepItLow() {
        ducking.voicePcm(voice, 0, voice.size)
        assertEquals(0.2f, ducking.mediaGain(), 0f)
        clock = 500
        ducking.voicePcm(ByteArray(20), 0, 20)
        assertEquals(0.2f, ducking.mediaGain(), 0f)
        clock = 700
        assertEquals(1f, ducking.mediaGain(), 0f)
    }

    @Test fun overlappingSpeechExtendsHoldAndCommandDoesNotDoubleAttenuate() {
        ducking.command(true)
        ducking.voicePcm(voice, 0, 2)
        assertEquals(0.2f, ducking.mediaGain(), 0f)
        clock = 600
        ducking.voicePcm(voice, 0, 2)
        ducking.command(false)
        clock = 800
        assertEquals(0.2f, ducking.mediaGain(), 0f)
        clock = 1300
        assertEquals(1f, ducking.mediaGain(), 0f)
    }

    @Test fun commandPersistsUntilUnduckAndResetClearsBothReasons() {
        ducking.command(true)
        clock = 10_000
        assertEquals(0.2f, ducking.mediaGain(), 0f)
        ducking.command(false)
        assertEquals(1f, ducking.mediaGain(), 0f)
        ducking.command(true)
        ducking.voicePcm(voice, 0, 2)
        ducking.reset()
        assertEquals(1f, ducking.mediaGain(), 0f)
    }

    @Test fun legacyNavigationAndNamedGuidanceArePromptsButMusicIsNot() {
        assertTrue(CrvAudioDucking.isPrompt(101, "default"))
        assertTrue(CrvAudioDucking.isPrompt(100, "guidance"))
        assertFalse(CrvAudioDucking.isPrompt(100, "media"))
        assertFalse(CrvAudioDucking.isPrompt(101, "media"))
        assertTrue(CrvAudioDucking.isMusic(100, "default"))
        assertTrue(CrvAudioDucking.isMusic(103, "media"))
        assertFalse(CrvAudioDucking.isMusic(101, "default"))
    }
}
