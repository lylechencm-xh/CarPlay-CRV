package com.shilapi.xcertplay.airplay

/** Serializes ownership changes with delivery; retired streams cannot stop their successors. */
class MediaSinkOwnership(private val sink: MediaSink) {
    private val lock = Any()
    private val audio = HashMap<AudioStreamId, Any>()
    private val screens = HashMap<Int, Any>()

    fun claimAudio(id: AudioStreamId, owner: Any) = synchronized(lock) {
        if (audio[id] !== owner) {
            if (audio.containsKey(id)) sink.onMicrophoneStopped(id)
            sink.onAudioStopped(id)
            audio[id] = owner
        }
    }
    fun deliverAudio(id: AudioStreamId, owner: Any, action: (MediaSink) -> Unit) = synchronized(lock) {
        if (audio[id] === owner) action(sink)
    }
    fun audioRtp(id: AudioStreamId, owner: Any, format: AudioFormat, rtp: ByteArray, sample: Int) = synchronized(lock) {
        if (audio[id] === owner) sink.onAudioRtp(id, format, rtp, sample)
    }
    fun audioRtp(id: AudioStreamId, owner: Any, format: AudioFormat, rtp: ByteArray, sample: Int, receivedNs: Long) = synchronized(lock) {
        if (audio[id] === owner) sink.onAudioRtp(id, format, rtp, sample, receivedNs)
    }
    fun releaseAudio(id: AudioStreamId, owner: Any): Boolean = synchronized(lock) {
        if (audio[id] !== owner) return false
        audio.remove(id)
        sink.onMicrophoneStopped(id)
        sink.onAudioStopped(id)
        true
    }
    fun claimScreen(type: Int, owner: Any) = synchronized(lock) {
        if (screens[type] !== owner) {
            if (screens.containsKey(type)) sink.onScreenStreamActive(type, false)
            screens[type] = owner
            sink.onScreenStreamActive(type, true)
        }
    }
    fun ownsScreen(type: Int, owner: Any): Boolean = synchronized(lock) { screens[type] === owner }
    fun releaseScreen(type: Int, owner: Any): Boolean = synchronized(lock) {
        if (screens[type] !== owner) return false
        screens.remove(type)
        sink.onScreenStreamActive(type, false)
        true
    }
    fun resetDuckingIfIdle() = synchronized(lock) {
        if (audio.isEmpty()) sink.onAudioDuckingChanged(false)
    }
    private inline fun screen(type: Int, owner: Any, action: (MediaSink) -> Unit) = synchronized(lock) {
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
