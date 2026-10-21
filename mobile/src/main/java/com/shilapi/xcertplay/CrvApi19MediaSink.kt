package com.shilapi.xcertplay

import android.content.Context
import android.media.AudioFormat as AndroidAudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Process
import android.view.Surface
import com.shilapi.xcertplay.airplay.AudioCodecKind
import com.shilapi.xcertplay.airplay.AudioFormat
import com.shilapi.xcertplay.airplay.AudioStreamId
import com.shilapi.xcertplay.airplay.MediaSink
import com.shilapi.xcertplay.airplay.MicrophoneConfig
import com.shilapi.xcertplay.airplay.VideoCodec
import com.shilapi.xcertplay.media.MediaCodecSupport
import java.io.Closeable
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Verifier-safe Android 4.2.2 / API17 media backend used by the 2021 CR-V build.
 *
 * Intentionally avoids every media API introduced after API19:
 * AudioAttributes, AudioFocusRequest, AudioTrack.Builder, AudioFormat.Builder,
 * MediaCodecList, getInputBuffer/getOutputBuffer and setOutputSurface.
 */
class CrvApi19MediaSink(
    context: Context,
    surface: Surface,
    private val videoWidth: Int,
    private val videoHeight: Int,
    private val report: (String) -> Unit = {},
    private val onVideoRendered: () -> Unit = {},
    private val onVideoReset: () -> Unit = {},
) : MediaSink, Closeable {
    override fun acceptsLengthPrefixedVideo(type: Int): Boolean = true
    @Volatile private var outputSurface: Surface? = surface
    private val videoLock = Any()
    private val videoCleanup = Executors.newSingleThreadExecutor { task ->
        Thread(task, "crv-video-cleanup").apply { isDaemon = true }
    }
    @Volatile private var videoVisible = true
    @Volatile private var videoReady = true
    private var surfaceGeneration = 0L
    private val navigationSettings = context.applicationContext
        .getSharedPreferences("crv_audio", Context.MODE_PRIVATE)

    override fun isVideoVisible(type: Int): Boolean = videoVisible && outputSurface != null && !closed.get()

    fun setVideoVisible(visible: Boolean) {
        if (visible && !closed.get() && audioRenderers.isNotEmpty() &&
            audioManager.mode != AudioManager.MODE_IN_CALL && !audioManager.isBluetoothScoOn) ensureAudioFocus()
        synchronized(videoLock) {
            if (closed.get() || videoVisible == visible) return
            videoVisible = visible
        }
        visibilityHandlers.values.toList().forEach { runCatching(it) }
        report("Video visibility=$visible; audio and transport retained")
        report("Audio visibility=$visible ${audioRouteSnapshot()}")
        updateSurface(outputSurface)
    }

    private val closed = AtomicBoolean(false)

    private val audioManager =
        context.applicationContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val audioFocusHeld = AtomicBoolean(false)
    @Volatile private var focusGain = 1f
    private val communicationMode = CrvCommunicationModeLease(
        { audioManager.mode }, { audioManager.mode = it }, { audioManager.isBluetoothScoOn })
    private val audioFocusListener = AudioManager.OnAudioFocusChangeListener { change ->
        when (change) {
            AudioManager.AUDIOFOCUS_GAIN -> {
                audioFocusHeld.set(true)
                focusGain = 1f
                report("Audio focus gained ${audioRouteSnapshot()}")
            }
            AudioManager.AUDIOFOCUS_LOSS -> {
                audioFocusHeld.set(false)
                focusGain = 0f
                report("Audio focus lost ${audioRouteSnapshot()}")
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                focusGain = if (change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK) 0.2f else 0f
                report("Audio focus transient change=$change ${audioRouteSnapshot()}")
            }
        }
    }

    private val videoCodecs = ConcurrentHashMap<Int, VideoCodec>()
    private val videoConfigs = ConcurrentHashMap<Int, ByteArray>()
    private val videoDecoders = ConcurrentHashMap<Int, LegacyVideoDecoder>()
    private val recoveryHandlers = ConcurrentHashMap<Int, () -> Unit>()
    private val visibilityHandlers = ConcurrentHashMap<Int, () -> Unit>()
    private val diagnosticHandlers = ConcurrentHashMap<Int, (String) -> Unit>()
    private val audioRenderers = ConcurrentHashMap<AudioStreamId, LegacyAudioRenderer>()
    private val audioDucking = CrvAudioDucking()
    private val microphones = ConcurrentHashMap<AudioStreamId, CrvApi19MicrophoneUplink>()

    override fun setVideoRecoveryHandler(type: Int, handler: () -> Unit) {
        if (!closed.get()) recoveryHandlers[type] = handler
    }

    override fun setVideoVisibilityHandler(type: Int, handler: () -> Unit) {
        if (!closed.get()) visibilityHandlers[type] = handler
    }

    override fun setVideoDiagnosticHandler(type: Int, handler: (String) -> Unit) {
        if (!closed.get()) diagnosticHandlers[type] = handler
    }

    override fun onVideoCodec(type: Int, codec: VideoCodec) {
        if (!closed.get()) videoCodecs[type] = codec
    }

    override fun onVideoConfig(type: Int, codecData: ByteArray) {
        if (closed.get()) return
        val codec = videoCodecs[type] ?: VideoCodec.H264
        synchronized(videoLock) {
            videoConfigs[type] = codecData.copyOf()
            val existing = videoDecoders[type]
            if (existing == null) decoder(type)
            else existing.configure(codec, codecData)
        }
    }

    fun recoverVideo() {
        synchronized(videoLock) {
            videoDecoders.values.forEach { it.requestRecovery() }
            recoveryHandlers.values.forEach { runCatching(it) }
        }
    }

    override fun onVideoFrame(type: Int, naluBytes: ByteArray) {
        if (closed.get()) return
        decoder(type)?.submit(naluBytes)
    }

    override fun onVideoFrame(type: Int, naluBytes: ByteArray, arrivalNs: Long) {
        if (!closed.get()) decoder(type)?.submit(naluBytes, arrivalNs)
    }

    override fun onVideoDiscontinuity(type: Int) {
        synchronized(videoLock) { videoDecoders[type]?.requestRecovery("video receive age limit") }
    }

    fun updateSurface(surface: Surface?, retired: () -> Unit = {}) {
        // No joins or codec operations on the Activity/Surface callback thread.
        synchronized(videoLock) {
            if (closed.get()) {
                // A late TextureView destruction can follow controller teardown. Wait off the UI
                // thread for already-submitted cleanup before releasing its producer/consumer.
                Thread({
                    try { while (!videoCleanup.awaitTermination(1L, java.util.concurrent.TimeUnit.SECONDS)) { } }
                    catch (_: InterruptedException) { Thread.currentThread().interrupt(); return@Thread }
                    retired()
                }, "crv-surface-retire").apply { isDaemon = true; start() }
                return
            }
            outputSurface = surface
            onVideoReset()
            videoReady = false
            val generation = ++surfaceGeneration
            val oldDecoders = videoDecoders.values.toList()
            videoDecoders.clear()
            oldDecoders.forEach { it.stop() }
            try {
                videoCleanup.execute {
                    oldDecoders.forEach { it.close() }
                    // A timed close may leave a vendor codec call in progress. Do not create a
                    // replacement on the same surface until the old worker actually releases it.
                    if (!oldDecoders.all { it.awaitStopped() }) return@execute
                    runCatching(retired)
                    val restore = synchronized(videoLock) {
                        if (closed.get() || generation != surfaceGeneration ||
                            !videoVisible || outputSurface == null) false
                        else { videoReady = true; true }
                    }
                    if (restore) {
                        report("Video surface ready generation=$generation; requesting fresh frame")
                        recoveryHandlers.values.toList().forEach { runCatching(it) }
                    }
                }
            } catch (_: RejectedExecutionException) {
                // close() has already retired the sink.
            }
        }
    }

    override fun onScreenStreamActive(type: Int, active: Boolean) {
        if (closed.get()) return
        if (type == 110) onVideoReset()
        if (!active) {
            val decoder = synchronized(videoLock) { videoDecoders.remove(type) }
            decoder?.close()
            videoCodecs.remove(type)
            videoConfigs.remove(type)
            recoveryHandlers.remove(type)
            visibilityHandlers.remove(type)
            diagnosticHandlers.remove(type)
        }
    }

    override fun onAudioStarted(id: AudioStreamId, format: AudioFormat, firstSample: Int) {
        if (closed.get()) return
        ensureAudioFocus()
        if (id.audioType == "telephony") runCatching { communicationMode.acquire("output:$id") }
        audioRenderers.remove(id)?.close()
        val isMusic = CrvAudioDucking.isMusic(id.type, id.audioType)
        val isPrompt = CrvAudioDucking.isPrompt(id.type, id.audioType)
        val isNavigation = CrvNavigationPcmGain.isNavigation(id.type, id.audioType)
        report("Audio route type=${id.type} audioType=${id.audioType} music=$isMusic navigation=$isNavigation")
        LegacyAudioRenderer(format, report,
            isMusic = isMusic,
            navigationGain = if (isNavigation) {
                { navigationSettings.getInt("navigation_gain_percent", 150).coerceIn(100, 300) / 100f }
            } else null,
            gain = { focusGain * if (isMusic) audioDucking.mediaGain() else 1f },
            voicePcm = { bytes, offset, count, playoutMs ->
                if (isPrompt) audioDucking.voicePcm(bytes, offset, count, playoutMs)
            },
        ).also {
            audioRenderers[id] = it
            it.start()
        }
    }

    override fun onAudioRtp(id: AudioStreamId, format: AudioFormat, rtp: ByteArray, sample: Int) {
        if (!closed.get()) audioRenderers[id]?.submit(rtp, sample)
    }

    override fun onAudioStopped(id: AudioStreamId) {
        audioRenderers.remove(id)?.close()
        if (id.audioType == "telephony") runCatching { communicationMode.release("output:$id") }
        if (audioRenderers.isEmpty()) abandonAudioFocus()
    }

    override fun onAudioDuckingChanged(duck: Boolean) {
        if (closed.get()) return
        audioDucking.command(duck)
        report("Audio music duck command=$duck")
    }

    override fun onMicrophoneStarted(id: AudioStreamId, config: MicrophoneConfig) {
        if (closed.get()) return
        microphones.remove(id)?.close()
        if (config.audioType == "telephony") runCatching { communicationMode.acquire("input:$id") }
        report("Audio microphone starting type=${config.audioType} ${audioRouteSnapshot()}")
        CrvApi19MicrophoneUplink(config, report).also { uplink ->
            if (uplink.start()) microphones[id] = uplink else {
                uplink.close()
                runCatching { communicationMode.release("input:$id") }
            }
        }
    }

    override fun onMicrophoneStopped(id: AudioStreamId) {
        microphones.remove(id)?.close()
        runCatching { communicationMode.release("input:$id") }
        report("Audio microphone stopped type=${id.audioType} ${audioRouteSnapshot()}")
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        audioDucking.reset()
        val decoders = synchronized(videoLock) {
            outputSurface = null
            videoDecoders.values.toList().also { videoDecoders.clear() }
        }
        decoders.forEach { it.stop() }
        videoCleanup.execute { decoders.forEach { it.close() }; decoders.forEach { it.awaitStopped() } }
        videoCleanup.shutdown()
        audioRenderers.values.toList().forEach { it.close() }
        audioRenderers.clear()
        abandonAudioFocus()
        microphones.values.toList().forEach { it.close() }
        microphones.clear()
        runCatching { communicationMode.close() }
        recoveryHandlers.clear()
        visibilityHandlers.clear()
        diagnosticHandlers.clear()
    }

    @Suppress("DEPRECATION")
    private fun ensureAudioFocus() {
        if (!audioFocusHeld.compareAndSet(false, true)) return
        val result = audioManager.requestAudioFocus(
            audioFocusListener,
            AudioManager.STREAM_MUSIC,
            AudioManager.AUDIOFOCUS_GAIN,
        )
        if (result != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            audioFocusHeld.set(false)
            report("Audio focus not granted")
        } else focusGain = 1f
    }

    @Suppress("DEPRECATION")
    private fun abandonAudioFocus() {
        if (!audioFocusHeld.compareAndSet(true, false)) return
        runCatching { audioManager.abandonAudioFocus(audioFocusListener) }
    }

    @Suppress("DEPRECATION")
    private fun audioRouteSnapshot(): String =
        "mode=${audioManager.mode} sco=${audioManager.isBluetoothScoOn} " +
            "speaker=${audioManager.isSpeakerphoneOn} wired=${audioManager.isWiredHeadsetOn}"

    private fun decoder(type: Int): LegacyVideoDecoder? =
        synchronized(videoLock) {
            if (closed.get() || !videoVisible || !videoReady) return@synchronized null
            videoDecoders[type]?.let { return@synchronized it }
            val surface = outputSurface ?: return@synchronized null
            lateinit var next: LegacyVideoDecoder
            next = LegacyVideoDecoder(
                surface = surface,
                width = videoWidth,
                height = videoHeight,
                requestKeyFrame = {
                    if (isVideoVisible(type) && videoReady) recoveryHandlers[type]?.invoke()
                },
                diagnostic = { line ->
                    val handler = diagnosticHandlers[type]
                    if (handler != null) handler(line) else report("Video: $line")
                },
                onRendered = {
                    synchronized(videoLock) {
                        if (type == 110 && videoDecoders[type] === next && videoVisible && videoReady && !closed.get()) {
                            onVideoRendered()
                        }
                    }
                },
            )
            next.also {
                videoDecoders[type] = next
                videoConfigs[type]?.let { data ->
                    next.configure(videoCodecs[type] ?: VideoCodec.H264, data)
                }
            }
        }

    private class LegacyVideoDecoder(
        private val surface: Surface,
        private val width: Int,
        private val height: Int,
        private val requestKeyFrame: () -> Unit,
        private val diagnostic: (String) -> Unit,
        private val onRendered: () -> Unit,
    ) : Closeable {
        private val queue = CrvVideoJobQueue(VIDEO_QUEUE_CAPACITY, VIDEO_QUEUE_MAX_BYTES)
        private val drainSchedule = CrvCodecDrainSchedule()
        private val backlogScratch = CrvVideoJobQueue.Backlog(0, null)
        private val running = AtomicBoolean(true)
        private val thread = Thread(::run, "crv-api19-video").apply {
            isDaemon = true
        }

        @Volatile private var decoder: MediaCodec? = null
        private var inputBuffers: Array<ByteBuffer>? = null
        private val outputInfo = MediaCodec.BufferInfo()
        private var lastCodec = VideoCodec.H264
        private var lastConfig: ByteArray? = null
        private var waitingForKeyFrame = true
        private var firstRendered = false
        private var lastRenderNotificationNs = 0L
        private var lastKeyFrameRequestNs = 0L
        private val backlogRecovery = CrvVideoBacklogRecovery()
        private val outputRecovery = CrvVideoOutputRecovery()
        private var decoderGeneration = 0L
        private var duplicateConfigs = 0L

        fun requestRecovery(reason: String = "video first output watchdog") {
            queue.recover(null, 0L, reason)
        }

        fun configure(codec: VideoCodec, data: ByteArray) {
            queue.offerConfig(codec, data.copyOf())
        }

        fun submit(data: ByteArray, receivedNs: Long = System.nanoTime()) {
            when (queue.offerFrame(data, receivedNs)) {
                CrvVideoJobQueue.FrameOffer.ADDED -> Unit
                CrvVideoJobQueue.FrameOffer.FRAME_LIMIT ->
                    queue.recover(data, receivedNs, "video queue frame limit")
                CrvVideoJobQueue.FrameOffer.BYTE_LIMIT ->
                    queue.recover(data, receivedNs, "video queue byte limit")
            }
        }

        fun stop() {
            running.set(false)
            thread.interrupt()
        }

        fun awaitStopped(): Boolean = try {
            if (thread !== Thread.currentThread()) thread.join()
            !thread.isAlive
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }

        override fun close() {
            stop()
            if (thread !== Thread.currentThread()) {
                try {
                    thread.join(WORKER_CLOSE_JOIN_MILLIS)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                }
            }
        }

        private fun run() {
            val job = CrvVideoJobQueue.Holder()
            try {
                // Best-effort: OEM firmware can reject priority changes; decoding must continue.
                runCatching { Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_DISPLAY) }
                while (running.get()) {
                    if (queue.poll(drainSchedule.queueWaitMillis(System.nanoTime()), job)) when (job.type) {
                        CrvVideoJobQueue.TYPE_CONFIG -> {
                            val codec = job.codec
                            val data = job.data
                            if (codec != null && data != null) configureNow(codec, data)
                        }
                        CrvVideoJobQueue.TYPE_FRAME -> {
                            val data = job.data ?: continue
                            val backlog = backlogAfterCurrent()
                            val overloaded = !waitingForKeyFrame && backlogRecovery.observe(
                                nowNs = System.nanoTime(),
                                currentReceivedNs = job.receivedNs,
                                pendingFrames = backlog.pendingFrames,
                                newestPendingReceivedNs = backlog.newestPendingReceivedNs,
                            )
                            if (overloaded) {
                                discardCurrentFrameChain()
                                recover(
                                    "video backlog kept growing or exceeded hard limit",
                                    preferFlush = true,
                                )
                            } else {
                                feed(data, job.receivedNs)
                            }
                        }
                        CrvVideoJobQueue.TYPE_RECOVER -> {
                            backlogRecovery.reset()
                            recover(job.reason ?: "video queue recovery", preferFlush = true)
                        }
                        else -> Unit
                    }
                    drain()
                }
            } catch (_: InterruptedException) {
                // Normal shutdown.
            } finally {
                releaseDecoder()
            }
        }

        private fun configureNow(videoCodec: VideoCodec, data: ByteArray) {
            if (!crvNeedsVideoConfiguration(decoder != null, lastCodec, lastConfig, videoCodec, data)) {
                duplicateConfigs++
                return
            }
            backlogRecovery.reset()
            diagnostic("config apply codec=$videoCodec bytes=${data.size} signature=${data.contentHashCode()} " +
                "reason=${if (decoder == null) "create-or-recover" else "changed"}")
            lastCodec = videoCodec
            lastConfig = data.copyOf()
            releaseDecoder()
            waitingForKeyFrame = true
            firstRendered = false

            if (videoCodec != VideoCodec.H264) {
                diagnostic("HEVC disabled on Android 4.2.2")
                requestKeyFrameIfDue()
                return
            }

            val (sps, pps) = MediaCodecSupport.avcParameterSets(data)
            val format = MediaFormat.createVideoFormat(
                "video/avc",
                width,
                height,
            )
            format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, MAX_VIDEO_INPUT)
            if (sps.isNotEmpty()) {
                format.setByteBuffer("csd-0", ByteBuffer.wrap(START_CODE + sps))
            }
            if (pps.isNotEmpty()) {
                format.setByteBuffer("csd-1", ByteBuffer.wrap(START_CODE + pps))
            }

            decoder = try {
                val codec = MediaCodec.createDecoderByType("video/avc")
                try {
                    codec.configure(format, surface, null, 0)
                    codec.start()
                    @Suppress("DEPRECATION")
                    val buffers = codec.inputBuffers
                    inputBuffers = buffers
                    decoderGeneration++
                    diagnostic("API17 H.264 decoder ready generation=$decoderGeneration")
                    codec
                } catch (error: Exception) {
                    runCatching { codec.release() }
                    throw error
                }
            } catch (error: Exception) {
                inputBuffers = null
                diagnostic("H.264 decoder failed: ${error.javaClass.simpleName}")
                null
            }
            requestKeyFrameIfDue()
        }

        private fun feed(packet: ByteArray, receivedNs: Long) {
            val feedStartNs = System.nanoTime()
            val config = lastConfig ?: return
            if (decoder == null) configureNow(lastCodec, config)
            val codec = decoder ?: return

            // Release/render available output before another potentially slow input copy/submit.
            // drain() may flush this same codec object, so don't feed an old-chain frame afterward.
            val beforeDrainGeneration = decoderGeneration
            drain()
            if (!running.get() || decoder !== codec || decoderGeneration != beforeDrainGeneration) return

            val inputSize = MediaCodecSupport.videoInputSize(packet)
            if (inputSize == 0) return

            // Once the reference chain is established, every valid interframe is fed unchanged.
            // Only recovery needs an IDR check; scanning Annex B payloads on every frame wastes
            // CPU on API17 head units while touch, decryption and audio share the same cores.
            val randomAccess = waitingForKeyFrame && MediaCodecSupport.isRandomAccessPacket(packet, lastCodec)
            if (waitingForKeyFrame) {
                if (!randomAccess) {
                    requestKeyFrameIfDue()
                    return
                }
                val backlog = backlogAfterCurrent()
                if (
                    CrvVideoRecoveryFrameAge.isObsolete(
                        nowNs = System.nanoTime(),
                        receivedNs = receivedNs,
                        pendingFrames = backlog.pendingFrames,
                        newestPendingReceivedNs = backlog.newestPendingReceivedNs,
                    )
                ) {
                    discardCurrentFrameChain()
                    backlogRecovery.reset()
                    diagnostic("obsolete recovery keyframe discarded; requesting fresh keyframe")
                    requestKeyFrameIfDue()
                    return
                }
            }

            try {
                val feedGeneration = decoderGeneration
                var index = -1
                var attempt = 0
                while (running.get() && attempt < VIDEO_INPUT_RETRY_ATTEMPTS) {
                    val inputWaitStartNs = System.nanoTime()
                    index = codec.dequeueInputBuffer(VIDEO_INPUT_TIMEOUT_US)
                    maxInputWaitNs = maxOf(maxInputWaitNs, System.nanoTime() - inputWaitStartNs)
                    if (index >= 0) break
                    drain()
                    // drain() can flush the same codec object while resolving input backpressure.
                    // A frame from the old reference chain must not enter that new generation.
                    if (decoder !== codec || decoderGeneration != feedGeneration) return
                    attempt++
                }
                if (index < 0) {
                    if (!queue.offerFrameFirst(packet, receivedNs)) {
                        queue.recover(null, 0L, "video decoder input backpressure")
                    }
                    return
                }
                val input = inputBuffers?.getOrNull(index) ?: run {
                    recover("video input buffer unavailable")
                    return
                }
                val copyStartNs = System.nanoTime()
                input.clear()
                if (inputSize > input.remaining() || !MediaCodecSupport.putVideoInput(packet, input)) {
                    codec.queueInputBuffer(index, 0, 0, 0L, 0)
                    recover("video frame exceeds decoder input")
                    return
                }
                maxInputCopyNs = maxOf(maxInputCopyNs, System.nanoTime() - copyStartNs)
                val inputSubmitStartNs = System.nanoTime()
                codec.queueInputBuffer(
                    index,
                    0,
                    inputSize,
                    receivedNs / 1000L,
                    0,
                )
                val submittedNs = System.nanoTime()
                maxInputSubmitNs = maxOf(maxInputSubmitNs, submittedNs - inputSubmitStartNs)
                drainSchedule.submitted(submittedNs)
                outputRecovery.submitted(receivedNs / 1000L, submittedNs)
                fedFrames++
                maxQueueAgeNs = maxOf(maxQueueAgeNs, (feedStartNs - receivedNs).coerceAtLeast(0L))
                maxFeedNs = maxOf(maxFeedNs, submittedNs - feedStartNs)
                if (randomAccess) waitingForKeyFrame = false
            } catch (error: Exception) {
                recover("video input failed: ${error.javaClass.simpleName}")
            }
        }

        private var fedFrames = 0L
        private var renderedFrames = 0L
        private var previousFedFrames = 0L
        private var previousRenderedFrames = 0L
        private var recoveryCount = 0L
        private var maxQueueAgeNs = 0L
        private var maxFeedNs = 0L
        private var maxInputWaitNs = 0L
        private var maxInputCopyNs = 0L
        private var maxInputSubmitNs = 0L
        private var maxOutputAgeUs = 0L
        private var maxRenderedAgeUs = 0L
        private var maxReleaseNs = 0L
        private val outputAges = com.shilapi.xcertplay.airplay.LatencyHistogram()
        private val decodedVideoOutput = CrvDecodedVideoOutput { index, render ->
            val start = System.nanoTime()
            decoder?.releaseOutputBuffer(index, render)
            maxReleaseNs = maxOf(maxReleaseNs, System.nanoTime() - start)
            if (render) {
                renderedFrames++
                val now = System.nanoTime()
                if (!firstRendered || now - lastRenderNotificationNs >= 1_000_000_000L) {
                    lastRenderNotificationNs = now
                    onRendered()
                }
            }
            if (render && !firstRendered) {
                firstRendered = true
                diagnostic("first frame rendered")
            }
        }
        private var videoOutputStatsAtNs = System.nanoTime()

        // Start after every decoder field is initialized; the worker can wake immediately.
        init { thread.start() }

        private fun drain() {
            val codec = decoder ?: return
            try {
                var newestOutputUs = 0L
                while (running.get()) {
                    val index = codec.dequeueOutputBuffer(outputInfo, 0L)
                    when {
                        index == MediaCodec.INFO_TRY_AGAIN_LATER -> break
                        index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> Unit
                        index == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED -> Unit
                        index >= 0 -> {
                            if (outputInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                                codec.releaseOutputBuffer(index, false)
                                continue
                            }
                            drainSchedule.outputReady()
                            newestOutputUs = outputInfo.presentationTimeUs
                            val ageUs = System.nanoTime() / 1000L - outputInfo.presentationTimeUs
                            if (outputInfo.presentationTimeUs != 0L && ageUs >= 0L) {
                                maxOutputAgeUs = maxOf(maxOutputAgeUs, ageUs)
                                outputAges.record(ageUs * 1000L)
                            }
                            decodedVideoOutput.offer(index)
                        }
                        else -> break
                    }
                }
                if (!waitingForKeyFrame && running.get() && newestOutputUs > 0L) {
                    maxRenderedAgeUs = maxOf(maxRenderedAgeUs,
                        (System.nanoTime() / 1000L - newestOutputUs).coerceAtLeast(0L))
                }
                decodedVideoOutput.finish(render = !waitingForKeyFrame && running.get())
                val now = System.nanoTime()
                if (!waitingForKeyFrame && running.get() &&
                    outputRecovery.observe(now, newestOutputUs)) {
                    discardCurrentFrameChain()
                    backlogRecovery.reset()
                    recover("video codec ${outputRecovery.reason}; ${outputRecovery.triggerSnapshot}; requesting fresh frame chain", preferFlush = true)
                }
                if (now - videoOutputStatsAtNs >= 5_000_000_000L) {
                    val windowMs = (now - videoOutputStatsAtNs) / 1_000_000L
                    diagnostic("decoded output generation=$decoderGeneration fedTotal=$fedFrames renderedTotal=$renderedFrames " +
                        "windowMs=$windowMs fedFrames=${fedFrames - previousFedFrames} " +
                        "displayedFrames=${renderedFrames - previousRenderedFrames} recoveriesTotal=$recoveryCount " +
                        "staleSkippedTotal=${decodedVideoOutput.discardedTotal} " +
                        "queueMaxMs=${maxQueueAgeNs / 1_000_000L} feedMaxUs=${maxFeedNs / 1000L} " +
                        "outputAgeMaxMs=${maxOutputAgeUs / 1000L} " +
                        "arrivalAgeP50Ms=${outputAges.percentileMs(50)} arrivalAgeP90Ms=${outputAges.percentileMs(90)} " +
                        "arrivalAgeP99Ms=${outputAges.percentileMs(99)} " +
                        "renderedAgeMaxMs=${maxRenderedAgeUs / 1000L} releaseMaxUs=${maxReleaseNs / 1000L} " +
                        "inputWaitMaxUs=${maxInputWaitNs / 1000L} inputCopyMaxUs=${maxInputCopyNs / 1000L} " +
                        "inputSubmitMaxUs=${maxInputSubmitNs / 1000L} duplicateConfigsTotal=$duplicateConfigs")
                    maxQueueAgeNs = 0L
                    maxFeedNs = 0L
                    maxInputWaitNs = 0L
                    maxInputCopyNs = 0L
                    maxInputSubmitNs = 0L
                    maxOutputAgeUs = 0L
                    maxRenderedAgeUs = 0L
                    maxReleaseNs = 0L
                    outputAges.reset()
                    previousFedFrames = fedFrames
                    previousRenderedFrames = renderedFrames
                    videoOutputStatsAtNs = now
                }
            } catch (error: Exception) {
                decodedVideoOutput.reset()
                recover("video output failed: ${error.javaClass.simpleName}")
            }
        }

        private fun backlogAfterCurrent(): CrvVideoJobQueue.Backlog = queue.backlogAfterCurrent(backlogScratch)

        private fun discardCurrentFrameChain() {
            queue.discardLeadingFrames()
        }

        private fun recover(reason: String, preferFlush: Boolean = false) {
            recoveryCount++
            diagnostic(reason)
            val active = decoder
            if (preferFlush && active != null && firstRendered) {
                // Android's synchronous MediaCodec resumes after flush on the next dequeueInputBuffer.
                // Start the new reference chain only from an IDR/key frame.
                val flushed = runCatching { active.flush() }
                    .onFailure { diagnostic("video decoder flush failed: " + it.javaClass.simpleName) }
                    .isSuccess
                if (flushed) {
                    decoderGeneration++
                    outputRecovery.reset()
                    drainSchedule.reset()
                    decodedVideoOutput.reset()
                    @Suppress("DEPRECATION")
                    val buffers = runCatching { active.inputBuffers }.getOrNull()
                    inputBuffers = buffers
                    waitingForKeyFrame = true
                    diagnostic("video decoder flushed; requesting keyframe")
                    requestKeyFrameIfDue()
                    return
                }
            } else if (preferFlush && active != null) {
                // Android warns that flushing before the first output can discard codec-specific
                // data. Recreate the API17 decoder so SPS/PPS are supplied again from lastConfig.
                diagnostic("video early recovery rebuilding decoder before first output")
            }
            releaseDecoder()
            waitingForKeyFrame = true
            firstRendered = false
            requestKeyFrameIfDue()
        }

        private fun requestKeyFrameIfDue() {
            val now = System.nanoTime()
            if (
                lastKeyFrameRequestNs != 0L &&
                now - lastKeyFrameRequestNs < KEYFRAME_INTERVAL_NS
            ) return
            lastKeyFrameRequestNs = now
            runCatching(requestKeyFrame)
        }

        private fun releaseDecoder() {
            outputRecovery.reset()
            drainSchedule.reset()
            decodedVideoOutput.reset()
            val codec = decoder ?: return
            decoder = null
            inputBuffers = null
            runCatching { codec.stop() }
            runCatching { codec.release() }
        }
    }

    private class LegacyAudioRenderer(
        private val format: AudioFormat,
        private val report: (String) -> Unit,
        private val isMusic: Boolean,
        private val navigationGain: (() -> Float)?,
        private val gain: () -> Float,
        private val voicePcm: (ByteArray, Int, Int, Long) -> Unit,
    ) : Closeable {
        private val queue = CrvAudioPacketQueue(
            AUDIO_QUEUE_CAPACITY,
            maxAgeNs = if (isMusic) 200_000_000L else 100_000_000L,
            maxPayloadBytes = if (format.codec == AudioCodecKind.LPCM) {
                format.sampleRate.toLong() * format.channels.coerceAtLeast(1) * 2 *
                    (if (isMusic) 200 else 100) / 1000
            } else Long.MAX_VALUE,
        )
        private val packetHolder = CrvAudioPacketQueue.MutablePacket()
        private val drainSchedule = CrvCodecDrainSchedule()
        private val running = AtomicBoolean(true)
        private val thread = Thread(::run, "crv-api19-audio").apply {
            isDaemon = true
        }

        private var started = false
        private val frameBytes = format.channels.coerceAtLeast(1) * 2
        private val pcmBatch = CrvPcmBatch(frameBytes,
            maxOf(frameBytes, format.sampleRate * frameBytes * 20 / 1000))
        private val ratePolicy = CrvAudioRatePolicy(format.sampleRate)
        private var appliedPlaybackRate = format.sampleRate
        private var rateCorrectionAvailable = true
        private var writeCalls = 0L
        private var batchedPackets = 0L
        private val bufferProgress = CrvAudioBufferProgress(frameBytes)
        @Volatile private var lastPacketNs = 0L
        private var decoder: MediaCodec? = null
        private var inputBuffers: Array<ByteBuffer>? = null
        private var outputBuffers: Array<ByteBuffer>? = null
        private val outputInfo = MediaCodec.BufferInfo()
        private var softwareOpus: CrvSoftwareOpusDecoder? = null
        private var rejectedOpusPackets = 0
        private var inputBackpressureDrops = 0
        private var pcmScratch = ByteArray(0)
        private var navigationScratch = ByteArray(0)
        private val navigationProcessor = CrvNavigationPcmGain()
        @Volatile private var track: AudioTrack? = null
        private var playbackStarted = false
        private var primedBytes = 0
        private var primeTargetBytes = 0
        private var lastPcmWriteNs = 0L
        private var rebufferCount = 0
        private var rebuffering = false
        private var freshPcmAfterPause = false
        private var drainingTail = false
        private var statsAtNs = System.nanoTime()
        private var maxWriteNs = 0L
        private var appliedGain = 1f
        private var gainAtNs = System.nanoTime()
        private var reportedDuck = false

        fun start() {
            if (started) return
            started = true
            thread.start()
        }

        fun submit(rtp: ByteArray, sample: Int) {
            if (!running.get()) return
            lastPacketNs = System.nanoTime()
            queue.offer(rtp, sample)
        }

        override fun close() {
            if (!running.compareAndSet(true, false)) return
            // stop() releases a blocking legacy write before waiting for the worker.
            // The worker remains the sole owner of final release().
            runCatching { track?.pause() }
            runCatching { track?.stop() }
            thread.interrupt()
            if (thread !== Thread.currentThread()) {
                try {
                    thread.join(WORKER_CLOSE_JOIN_MILLIS)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                }
            }
        }

        private fun run() {
            try {
                runCatching { Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO) }
                createTrack()
                configureDecoder()
                // Prime MODE_STREAM before play(); starting an empty track produces an
                // immediate underrun on this older audio stack.
                while (running.get()) {
                    updateGain()
                    if (queue.poll(drainSchedule.queueWaitMillis(System.nanoTime()), packetHolder)) {
                        val rtp = packetHolder.rtp
                        if (rtp != null) {
                            if (format.codec == AudioCodecKind.LPCM && isMusic) {
                                handlePcmBatch(rtp)
                            } else handle(rtp, packetHolder.sample)
                        }
                        packetHolder.rtp = null
                    }
                    drainDecoder()
                    maintainPlaybackBuffer()
                    reportAudioStats()
                }
            } catch (_: InterruptedException) {
                // Normal shutdown.
            } catch (error: Exception) {
                report("Audio renderer failed: ${error.javaClass.simpleName}")
            } finally {
                reportAudioStats(ended = true)
                release()
            }
        }

        private fun createTrack() {
            val channelMask = if (format.channels >= 2) {
                AndroidAudioFormat.CHANNEL_OUT_STEREO
            } else {
                AndroidAudioFormat.CHANNEL_OUT_MONO
            }
            val encoding = AndroidAudioFormat.ENCODING_PCM_16BIT
            val minimum = AudioTrack.getMinBufferSize(
                format.sampleRate,
                channelMask,
                encoding,
            )
            if (minimum <= 0) {
                report("AudioTrack unavailable")
                return
            }

            val bytesPerSecond = format.sampleRate * frameBytes
            val isMedia = isMusic
            val primeMillis = if (isMedia) MEDIA_AUDIO_PRIME_MILLIS else LOW_LATENCY_AUDIO_PRIME_MILLIS
            val preferredBufferBytes = CrvAudioBufferPolicy.preferred(minimum, bytesPerSecond, isMedia)
            val fallbackBufferBytes = maxOf(minimum * 4, MIN_AUDIO_TRACK_BUFFER_BYTES)

            fun buildTrack(bufferBytes: Int): AudioTrack? {
                val candidate = runCatching {
                    @Suppress("DEPRECATION")
                    AudioTrack(
                        AudioManager.STREAM_MUSIC,
                        format.sampleRate,
                        channelMask,
                        encoding,
                        bufferBytes,
                        AudioTrack.MODE_STREAM,
                    )
                }.getOrNull() ?: return null
                if (candidate.state == AudioTrack.STATE_INITIALIZED) return candidate
                candidate.release()
                return null
            }

            var selectedBufferBytes = preferredBufferBytes
            var audio = buildTrack(preferredBufferBytes)
            if (audio == null && preferredBufferBytes != fallbackBufferBytes) {
                report(
                    "AudioTrack media buffer rejected bytes=$preferredBufferBytes; " +
                        "retrying bytes=$fallbackBufferBytes",
                )
                selectedBufferBytes = fallbackBufferBytes
                audio = buildTrack(fallbackBufferBytes)
            }
            if (audio == null) {
                report("AudioTrack failed to initialize")
                return
            }
            track = audio
            updateGain(immediate = true)
            val plannedPrimeBytes = bytesPerSecond * primeMillis / 1000
            val safePrimeLimit = (selectedBufferBytes * 3 / 4).coerceAtLeast(frameBytes)
            primeTargetBytes = minOf(plannedPrimeBytes, safePrimeLimit).coerceAtLeast(frameBytes)
            report(
                "Audio API17 ready: ${format.codec} ${format.sampleRate}Hz " +
                    "channels=${format.channels} audioType=${format.audioType} " +
                    "bufferBytes=$selectedBufferBytes " +
                    "primeMs=$primeMillis primeBytes=$primeTargetBytes",
            )
        }

        private fun configureDecoder() {
            drainSchedule.reset()
            val mime = when (format.codec) {
                AudioCodecKind.LPCM -> return
                AudioCodecKind.AAC_LC -> "audio/mp4a-latm"
                AudioCodecKind.OPUS -> "audio/opus"
            }

            val mediaFormat = MediaFormat().apply {
                setString(MediaFormat.KEY_MIME, mime)
                setInteger(MediaFormat.KEY_SAMPLE_RATE, format.sampleRate)
                setInteger(MediaFormat.KEY_CHANNEL_COUNT, format.channels)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 64 * 1024)
                if (format.codec == AudioCodecKind.AAC_LC) {
                    setInteger("is-adts", 1)
                    setByteBuffer("csd-0", ByteBuffer.wrap(aacAudioSpecificConfig()))
                }
            }

            decoder = try {
                val candidate = MediaCodec.createDecoderByType(mime)
                try {
                    candidate.configure(mediaFormat, null, null, 0)
                    candidate.start()
                    @Suppress("DEPRECATION")
                    val inputs = candidate.inputBuffers
                    @Suppress("DEPRECATION")
                    val outputs = candidate.outputBuffers
                    inputBuffers = inputs
                    outputBuffers = outputs
                    candidate
                } catch (error: Exception) {
                    inputBuffers = null
                    outputBuffers = null
                    runCatching { candidate.release() }
                    throw error
                }
            } catch (error: Exception) {
                if (!activateOpusFallback("hardware unavailable: " + error.javaClass.simpleName)) {
                    report("Audio decoder unavailable: ${format.codec}")
                }
                null
            }
        }

        private fun activateOpusFallback(reason: String): Boolean {
            drainSchedule.reset()
            if (format.codec != AudioCodecKind.OPUS) return false
            if (softwareOpus != null) return true
            decoder?.let { codec ->
                runCatching { codec.stop() }
                runCatching { codec.release() }
            }
            decoder = null
            inputBuffers = null
            outputBuffers = null
            softwareOpus = runCatching {
                CrvSoftwareOpusDecoder(format.sampleRate, format.channels)
            }.onFailure { error ->
                report("Opus software decoder failed: " + error.javaClass.simpleName)
            }.getOrNull()
            if (softwareOpus != null) {
                report("Opus fallback=Concentus reason=" + reason)
                return true
            }
            return false
        }

        private fun handle(rtp: ByteArray, sample: Int) {
            val payloadStart = CrvLegacyRtp.payloadStart(rtp)
            val payloadEnd = CrvLegacyRtp.payloadEnd(rtp, payloadStart)
            if (payloadStart < 0 || payloadEnd <= payloadStart) return
            val payloadSize = payloadEnd - payloadStart

            when (format.codec) {
                AudioCodecKind.LPCM -> {
                    var index = payloadStart
                    while (index + 1 < payloadEnd) {
                        val first = rtp[index]
                        rtp[index] = rtp[index + 1]
                        rtp[index + 1] = first
                        index += 2
                    }
                    writePcm(rtp, payloadSize, payloadStart)
                }

                AudioCodecKind.AAC_LC -> {
                    feedDecoder(
                        bytes = rtp,
                        timestampUs = sampleTimestampUs(sample),
                        byteOffset = payloadStart,
                        byteCount = payloadSize,
                        addAdtsHeader = true,
                    )
                }

                AudioCodecKind.OPUS -> {
                    val software = softwareOpus
                    if (software != null) {
                        try {
                            val decodedBytes = software.decode(rtp, payloadStart, payloadSize)
                            writePcm(software.pcm, decodedBytes)
                        } catch (error: Exception) {
                            rejectedOpusPackets++
                            if (rejectedOpusPackets == 1 || rejectedOpusPackets % 100 == 0) {
                                report("Opus packet rejected count=" + rejectedOpusPackets +
                                    " type=" + error.javaClass.simpleName)
                            }
                        }
                    } else {
                        feedDecoder(
                            rtp,
                            sampleTimestampUs(sample),
                            payloadStart,
                            payloadSize,
                        )
                    }
                }
            }
        }

        private fun handlePcmBatch(first: ByteArray) {
            pcmBatch.clear()
            if (pcmBatch.append(first)) batchedPackets++
            // Never wait to fill a batch. Bound each pass so playback maintenance stays responsive.
            var packets = 1
            while (running.get() && !pcmBatch.full && packets < 8 && queue.poll(0L, packetHolder)) {
                packetHolder.rtp?.let { if (pcmBatch.append(it)) batchedPackets++ }
                packets++
            }
            if (pcmBatch.size > 0) writePcm(pcmBatch.bytes, pcmBatch.size)
        }

        private fun feedDecoder(
            bytes: ByteArray,
            timestampUs: Long,
            byteOffset: Int = 0,
            byteCount: Int = bytes.size - byteOffset,
            addAdtsHeader: Boolean = false,
        ) {
            val codec = decoder ?: return
            try {
                var index = -1
                var attempt = 0
                while (running.get() && attempt < AUDIO_INPUT_RETRY_ATTEMPTS) {
                    index = codec.dequeueInputBuffer(AUDIO_INPUT_TIMEOUT_US)
                    if (index >= 0) break
                    drainDecoder()
                    if (decoder !== codec) return
                    attempt++
                }
                if (index < 0) {
                    inputBackpressureDrops++
                    if (inputBackpressureDrops == 1 || inputBackpressureDrops % 100 == 0) {
                        report("Audio decoder input backpressure drops=$inputBackpressureDrops")
                    }
                    return
                }
                val input = inputBuffers?.getOrNull(index) ?: run {
                    codec.queueInputBuffer(index, 0, 0, 0L, 0)
                    report("Audio decoder input buffer unavailable")
                    return
                }
                input.clear()
                if (byteOffset < 0 || byteCount <= 0 || byteOffset + byteCount > bytes.size) {
                    codec.queueInputBuffer(index, 0, 0, 0L, 0)
                    return
                }
                val headerBytes = if (addAdtsHeader) CrvAacAdts.HEADER_BYTES else 0
                val inputBytes = byteCount + headerBytes
                if (inputBytes > input.remaining()) {
                    codec.queueInputBuffer(index, 0, 0, 0L, 0)
                    return
                }
                if (addAdtsHeader) {
                    CrvAacAdts.putHeader(
                        target = input,
                        accessUnitBytes = byteCount,
                        sampleRate = format.sampleRate,
                        channels = format.channels,
                    )
                }
                input.put(bytes, byteOffset, byteCount)
                codec.queueInputBuffer(index, 0, inputBytes, timestampUs, 0)
                drainSchedule.submitted(System.nanoTime())
            } catch (error: Exception) {
                if (!activateOpusFallback("hardware input failure: " + error.javaClass.simpleName)) {
                    report("Audio decoder input failed")
                }
            }
        }

        private fun drainDecoder() {
            val codec = decoder ?: return
            try {
                while (running.get()) {
                    val index = codec.dequeueOutputBuffer(outputInfo, 0L)
                    when {
                        index == MediaCodec.INFO_TRY_AGAIN_LATER -> return
                        index == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED -> {
                            @Suppress("DEPRECATION")
                            val buffers = codec.outputBuffers
                            outputBuffers = buffers
                        }
                        index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> Unit
                        index >= 0 -> {
                            if (outputInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                                drainSchedule.outputReady()
                            }
                            var copiedBytes = 0
                            try {
                                if (outputInfo.size > 0) {
                                    val output = outputBuffers?.getOrNull(index)
                                        ?: throw IllegalStateException("Audio decoder output buffer unavailable")
                                    if (pcmScratch.size < outputInfo.size) {
                                        pcmScratch = ByteArray(outputInfo.size)
                                    }
                                    output.position(outputInfo.offset)
                                    output.limit(outputInfo.offset + outputInfo.size)
                                    output.get(pcmScratch, 0, outputInfo.size)
                                    copiedBytes = outputInfo.size
                                }
                            } finally {
                                // AudioTrack.write() may block on this old audio HAL. Release the
                                // codec output slot first so decoder output cannot backpressure input.
                                codec.releaseOutputBuffer(index, false)
                            }
                            if (copiedBytes > 0) writePcm(pcmScratch, copiedBytes)
                        }
                        else -> return
                    }
                }
            } catch (error: Exception) {
                if (!activateOpusFallback("hardware output failure: " + error.javaClass.simpleName)) {
                    report("Audio decoder output failed")
                }
            }
        }

        private fun writePcm(
            bytes: ByteArray,
            byteCount: Int = bytes.size,
            byteOffset: Int = 0,
        ) {
            val audio = track ?: return
            if (byteOffset < 0 || byteCount <= 0 || byteOffset + byteCount > bytes.size) return
            if (byteCount % frameBytes != 0) {
                report("Audio PCM rejected unaligned bytes=$byteCount frameBytes=$frameBytes type=${format.audioType}")
                return
            }
            drainingTail = false // Newly decoded PCM ends the idle-tail episode.
            val playoutBytes = bufferProgress.queuedBytes(audio.playbackHeadPosition) + byteCount
            voicePcm(bytes, byteOffset, byteCount,
                playoutBytes * 1000L / (format.sampleRate.toLong() * frameBytes))
            updateGain()
            val writeBytes: ByteArray
            val writeOffset: Int
            if (navigationGain != null) {
                if (navigationScratch.size < byteCount) navigationScratch = ByteArray(byteCount)
                navigationProcessor.apply(bytes, byteOffset, byteCount, navigationScratch, navigationGain.invoke())
                writeBytes = navigationScratch
                writeOffset = 0
            } else {
                writeBytes = bytes
                writeOffset = byteOffset
            }
            var offset = 0
            while (offset < byteCount && running.get()) {
                val writeStartNs = System.nanoTime()
                val requestedBytes = CrvAudioPrimeWriteSize.next(
                    byteCount - offset, playbackStarted, primeTargetBytes, primedBytes)
                @Suppress("DEPRECATION")
                val written = audio.write(writeBytes, writeOffset + offset, requestedBytes)
                writeCalls++
                maxWriteNs = maxOf(maxWriteNs, System.nanoTime() - writeStartNs)
                if (written <= 0) {
                    if (running.get()) report("Audio write failed type=${format.audioType} code=$written")
                    return
                }
                offset += written
                bufferProgress.written(written)
                lastPcmWriteNs = System.nanoTime()
                if (!playbackStarted) {
                    if (rebuffering) freshPcmAfterPause = true
                    primedBytes += written
                    if (primedBytes >= primeTargetBytes) {
                        audio.play()
                        playbackStarted = true
                        rebuffering = false
                    }
                }
            }
        }

        @Suppress("DEPRECATION")
        private fun updateGain(immediate: Boolean = false) {
            val audio = track ?: return
            val target = gain()
            val now = System.nanoTime()
            val step = ((now - gainAtNs).coerceAtLeast(0L) / 150_000_000.0).toFloat()
            gainAtNs = now
            val next = if (immediate) target else if (target < appliedGain) {
                maxOf(target, appliedGain - step)
            } else minOf(target, appliedGain + step)
            if (immediate || next != appliedGain) {
                val result = audio.setStereoVolume(next, next)
                if (result == AudioTrack.SUCCESS) appliedGain = next
                else if (immediate) report("Audio volume rejected type=${format.audioType} code=$result")
            }
            val duck = target < 1f
            if (duck != reportedDuck) {
                reportedDuck = duck
                runCatching { report("Audio music duck=$duck target=$target type=${format.audioType}") }
            }
        }

        private fun reportAudioStats(ended: Boolean = false) {
            val now = System.nanoTime()
            if (!ended && now - statsAtNs < 5_000_000_000L) return
            val stats = queue.stats()
            val pcmQueueMs = if (format.codec == AudioCodecKind.LPCM) {
                stats.payloadBytes * 1000L / (format.sampleRate.toLong() * frameBytes)
            } else -1L
            val trackBytes = track?.let { bufferProgress.queuedBytes(it.playbackHeadPosition) } ?: 0L
            runCatching { report("Audio playback type=${format.audioType} queueDepth=${stats.depth} " +
                "queueHighWater=${stats.highWater} droppedTotal=${stats.dropped} expiredTotal=${stats.expired} " +
                "queuePayloadBytes=${stats.payloadBytes} queuePcmMs=$pcmQueueMs " +
                "oldestPacketMs=${stats.oldestAgeNs / 1_000_000L} trackQueuedBytes=$trackBytes " +
                "trackQueuedMs=${trackBytes * 1000L / (format.sampleRate.toLong() * frameBytes)} " +
                "playbackRateHz=$appliedPlaybackRate writeCallsTotal=$writeCalls batchedPacketsTotal=$batchedPackets " +
                "navigation=${navigationGain != null} navigationGain=${navigationProcessor.appliedGain} " +
                "navigationInputPeak=${navigationProcessor.inputPeak} " +
                "writeMaxUs=${maxWriteNs / 1000} rebufferTotal=$rebufferCount ended=$ended") }
            maxWriteNs = 0L
            statsAtNs = now
        }

        private fun maintainPlaybackBuffer() {
            val audio = track ?: return
            if (!isMusic) return

            val now = System.nanoTime()
            if (format.codec == AudioCodecKind.LPCM && rateCorrectionAvailable) {
                val queueMs = queue.queuedPayloadBytes() * 1000L / (format.sampleRate.toLong() * frameBytes)
                val desired = ratePolicy.desiredRate(now, queueMs, playbackStarted)
                if (desired != appliedPlaybackRate) {
                    val result = runCatching { audio.setPlaybackRate(desired) }.getOrDefault(AudioTrack.ERROR)
                    if (result == AudioTrack.SUCCESS) appliedPlaybackRate = desired
                    else {
                        // Stop attempting the hint on OEMs that reject it; restore nominal speed.
                        runCatching { audio.setPlaybackRate(format.sampleRate) }
                            .onSuccess { if (it == AudioTrack.SUCCESS) appliedPlaybackRate = format.sampleRate }
                        rateCorrectionAvailable = false
                        report("Audio rate correction unavailable code=$result rate=$appliedPlaybackRate")
                    }
                }
            }
            val lastPacket = lastPacketNs
            val queuedBytes = if (playbackStarted) {
                bufferProgress.queuedBytes(audio.playbackHeadPosition)
            } else {
                primedBytes.toLong()
            }
            val recoveryFloor = CrvMediaRebufferPolicy.recoveryFloorBytes(
                primeTargetBytes, format.sampleRate, frameBytes)
            if (
                CrvMediaRebufferPolicy.shouldPause(
                    isMedia = true,
                    playbackStarted = playbackStarted,
                    compressedQueueEmpty = queue.isEmpty(),
                    lastPacketNs = lastPacket,
                    nowNs = now,
                    queuedBytes = queuedBytes,
                    recoveryFloorBytes = recoveryFloor,
                    minimumPacketGapNs = MEDIA_REBUFFER_GAP_NS,
                    drainingTail = drainingTail,
                )
            ) {
                val paused = runCatching { audio.pause() }.isSuccess
                if (paused) {
                    playbackStarted = false
                    rebuffering = true
                    freshPcmAfterPause = false
                    primedBytes = queuedBytes.coerceAtMost(primeTargetBytes.toLong()).toInt()
                    lastPcmWriteNs = now
                    rebufferCount++
                    report(
                        "Audio media rebuffer count=$rebufferCount queuedBytes=$queuedBytes " +
                            "targetBytes=$primeTargetBytes floorBytes=$recoveryFloor " +
                            "packetGapMs=${(now - lastPacket).coerceAtLeast(0L) / 1_000_000L}",
                    )
                }
            }

            // A recovered short tail must not wait forever for an unreachable full prebuffer.
            // Require fresh PCM and a longer idle period so old paused data cannot flap play/pause.
            if (CrvMediaTailResumePolicy.shouldResume(
                    rebuffering, freshPcmAfterPause, playbackStarted, primedBytes,
                    queue.isEmpty(), lastPcmWriteNs, lastPacket, now)) {
                if (runCatching { audio.play() }.isSuccess) {
                    playbackStarted = true
                    val wasRebuffering = rebuffering
                    drainingTail = true
                    rebuffering = false
                    freshPcmAfterPause = false
                    report("Audio media tail resumed bytes=$primedBytes recovered=$wasRebuffering")
                }
            }
        }

        private fun sampleTimestampUs(sample: Int): Long =
            (sample.toLong() and 0xffff_ffffL) * 1_000_000L / format.sampleRate

        private fun aacAudioSpecificConfig(): ByteArray {
            val frequencyIndex =
                MediaCodecSupport.aacFrequencyIndex(format.sampleRate)
            val value =
                (AAC_LC_OBJECT_TYPE shl 11) or
                    (frequencyIndex shl 7) or
                    (format.channels.coerceIn(1, 7) shl 3)
            return byteArrayOf(
                (value ushr 8).toByte(),
                value.toByte(),
            )
        }

        private fun release() {
            drainSchedule.reset()
            decoder?.let { codec ->
                runCatching { codec.stop() }
                runCatching { codec.release() }
            }
            decoder = null
            inputBuffers = null
            outputBuffers = null
            softwareOpus = null

            track?.let { audio ->
                runCatching { audio.stop() }
                runCatching { audio.release() }
            }
            track = null
        }
    }

    private companion object {
        const val VIDEO_QUEUE_CAPACITY = 32
        const val VIDEO_QUEUE_MAX_BYTES = 8 * 1024 * 1024
        const val AUDIO_QUEUE_CAPACITY = 96
        const val LOW_LATENCY_AUDIO_PRIME_MILLIS = 30
        const val MEDIA_AUDIO_PRIME_MILLIS = 120
        const val MEDIA_AUDIO_HEADROOM_MILLIS = 100
        const val MEDIA_REBUFFER_GAP_NS = 80_000_000L
        const val MIN_AUDIO_TRACK_BUFFER_BYTES = 16 * 1024
        const val MAX_VIDEO_INPUT = 8 * 1024 * 1024
        const val VIDEO_INPUT_TIMEOUT_US = 2_000L
        const val VIDEO_INPUT_RETRY_ATTEMPTS = 3
        const val AUDIO_INPUT_TIMEOUT_US = 10_000L
        const val AUDIO_INPUT_RETRY_ATTEMPTS = 3
        const val AAC_LC_OBJECT_TYPE = 2
        const val KEYFRAME_INTERVAL_NS = 1_000_000_000L
        const val WORKER_CLOSE_JOIN_MILLIS = 750L
        val START_CODE = byteArrayOf(0x00, 0x00, 0x00, 0x01)
    }
}
