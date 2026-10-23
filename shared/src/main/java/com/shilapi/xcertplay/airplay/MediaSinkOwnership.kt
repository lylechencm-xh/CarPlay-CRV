package com.shilapi.xcertplay.airplay

import java.util.concurrent.ConcurrentHashMap

/** Per-stream serialization fences retired owners without blocking unrelated media on HAL cleanup. */
class MediaSinkOwnership(private val sink: MediaSink) {
    // Locks remain stable for the lifetime of the engine, including across stream replacement.
    private val audioLocks = ConcurrentHashMap<AudioStreamId, Any>()
    private val screenLocks = ConcurrentHashMap<Int, Any>()
    private val audio = ConcurrentHashMap<AudioStreamId, Any>()
    private val screens = ConcurrentHashMap<Int, Any>()
    private fun audioLock(id: AudioStreamId) = audioLocks.getOrPut(id) { Any() }
    private fun screenLock(type: Int) = screenLocks.getOrPut(type) { Any() }

    fun claimAudio(id: AudioStreamId, owner: Any) = synchronized(audioLock(id)) {
        if (audio[id] !== owner) {
            if (audio.containsKey(id)) sink.onMicrophoneStopped(id)
            sink.onAudioStopped(id)
            synchronized(audio) { audio[id] = owner }
        }
    }
    fun deliverAudio(id: AudioStreamId, owner: Any, action: (MediaSink) -> Unit) = synchronized(audioLock(id)) {
        if (audio[id] === owner) action(sink)
    }
    fun audioRtp(id: AudioStreamId, owner: Any, format: AudioFormat, rtp: ByteArray, sample: Int) = synchronized(audioLock(id)) {
        if (audio[id] === owner) sink.onAudioRtp(id, format, rtp, sample)
    }
    fun audioRtp(id: AudioStreamId, owner: Any, format: AudioFormat, rtp: ByteArray, sample: Int, receivedNs: Long) = synchronized(audioLock(id)) {
        if (audio[id] === owner) sink.onAudioRtp(id, format, rtp, sample, receivedNs)
    }
    fun releaseAudio(id: AudioStreamId, owner: Any): Boolean = synchronized(audioLock(id)) {
        if (audio[id] !== owner) return false
        synchronized(audio) { audio.remove(id) }
        sink.onMicrophoneStopped(id)
        sink.onAudioStopped(id)
        true
    }
    fun claimScreen(type: Int, owner: Any) = synchronized(screenLock(type)) {
        if (screens[type] !== owner) {
            if (screens.containsKey(type)) sink.onScreenStreamActive(type, false)
            screens[type] = owner
            sink.onScreenStreamActive(type, true)
        }
    }
    fun ownsScreen(type: Int, owner: Any): Boolean = synchronized(screenLock(type)) { screens[type] === owner }
    fun releaseScreen(type: Int, owner: Any): Boolean = synchronized(screenLock(type)) {
        if (screens[type] !== owner) return false
        screens.remove(type)
        sink.onScreenStreamActive(type, false)
        true
    }
    fun resetDuckingIfIdle() = synchronized(audio) {
        if (audio.isEmpty()) sink.onAudioDuckingChanged(false)
    }
    private inline fun screen(type: Int, owner: Any, action: (MediaSink) -> Unit) = synchronized(screenLock(type)) {
        if (screens[type] === owner) action(sink)
    }
    fun screenSink(type: Int, owner: Any): MediaSink = object : MediaSink {
        override fun isVideoVisible(type: Int): Boolean = ownsScreen(type, owner) && sink.isVideoVisible(type)
        override fun acceptsLengthPrefixedVideo(type: Int): Boolean = sink.acceptsLengthPrefixedVideo(type)
        override fun onVideoCodec(type: Int, codec: VideoCodec) = screen(type, owner) { it.onVideoCodec(type, codec) }
        override fun onVideoConfig(type: Int, codecData: ByteArray) = screen(type, owner) { it.onVideoConfig(type, codecData) }
        override fun onVideoFrame(type: Int, naluBytes: ByteArray) = screen(type, owner) { it.onVideoFrame(type, naluBytes) }
        override fun onVideoFrame(type: Int, naluBytes: ByteArray, arrivalNs: Long) = screen(type, owner) { it.onVideoFrame(type, naluBytes, arrivalNs) }
        override fun onVideoDiscontinuity(type: Int) = screen(type, owner) { it.onVideoDiscontinuity(type) }
        override fun setVideoRecoveryHandler(type: Int, handler: () -> Unit) = screen(type, owner) { it.setVideoRecoveryHandler(type, handler) }
        override fun setVideoVisibilityHandler(type: Int, handler: () -> Unit) = screen(type, owner) { it.setVideoVisibilityHandler(type, handler) }
        override fun setVideoDiagnosticHandler(type: Int, handler: (String) -> Unit) = screen(type, owner) { it.setVideoDiagnosticHandler(type, handler) }
    }
}
