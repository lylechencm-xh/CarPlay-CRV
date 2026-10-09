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
import java.util.concurrent.LinkedBlockingDeque
import java.util.concurrent.TimeUnit
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
) : MediaSink, Closeable {
    @Volatile private var outputSurface: Surface? = surface
    private val videoLock = Any()
    private val closed = AtomicBoolean(false)

    private val audioManager =
        context.applicationContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val audioFocusHeld = AtomicBoolean(false)
    private val audioFocusListener = AudioManager.OnAudioFocusChangeListener { change ->
        when (change) {
            AudioManager.AUDIOFOCUS_GAIN -> report("Audio focus gained")
            AudioManager.AUDIOFOCUS_LOSS -> {
                audioFocusHeld.set(false)
                report("Audio focus lost")
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> report("Audio focus transient")
        }
    }

    private val videoCodecs = ConcurrentHashMap<Int, VideoCodec>()
    private val videoConfigs = ConcurrentHashMap<Int, ByteArray>()
    private val videoDecoders = ConcurrentHashMap<Int, LegacyVideoDecoder>()
    private val recoveryHandlers = ConcurrentHashMap<Int, () -> Unit>()
    private val diagnosticHandlers = ConcurrentHashMap<Int, (String) -> Unit>()
    private val audioRenderers = ConcurrentHashMap<AudioStreamId, LegacyAudioRenderer>()
    private val microphones = ConcurrentHashMap<AudioStreamId, CrvApi19MicrophoneUplink>()

    override fun setVideoRecoveryHandler(type: Int, handler: () -> Unit) {
        if (!closed.get()) recoveryHandlers[type] = handler
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
        videoConfigs[type] = codecData.copyOf()
        decoder(type)?.configure(codec, codecData)
    }

    override fun onVideoFrame(type: Int, naluBytes: ByteArray) {
        if (closed.get()) return
        decoder(type)?.submit(naluBytes)
    }

    fun updateSurface(surface: Surface?) {
        if (closed.get()) return
        val oldDecoders = synchronized(videoLock) {
            if (closed.get()) return
            outputSurface = surface
            videoDecoders.values.toList().also { videoDecoders.clear() }
        }
        oldDecoders.forEach { it.close() }
        if (surface != null) {
            report("Video surface attached")
            recoveryHandlers.values.toList().forEach { runCatching(it) }
        } else {
            report("Video surface detached; transport kept alive")
        }
    }

    override fun onScreenStreamActive(type: Int, active: Boolean) {
        if (closed.get()) return
        if (!active) {
            val decoder = synchronized(videoLock) { videoDecoders.remove(type) }
            decoder?.close()
            videoCodecs.remove(type)
            videoConfigs.remove(type)
            recoveryHandlers.remove(type)
            diagnosticHandlers.remove(type)
        }
    }

    override fun onAudioStarted(id: AudioStreamId, format: AudioFormat, firstSample: Int) {
        if (closed.get()) return
        ensureAudioFocus()
        audioRenderers.remove(id)?.close()
        LegacyAudioRenderer(format, report).also {
            audioRenderers[id] = it
            it.start()
        }
    }

    override fun onAudioRtp(id: AudioStreamId, format: AudioFormat, rtp: ByteArray, sample: Int) {
        if (!closed.get()) audioRenderers[id]?.submit(rtp, sample)
    }

    override fun onAudioStopped(id: AudioStreamId) {
        audioRenderers.remove(id)?.close()
        if (audioRenderers.isEmpty()) abandonAudioFocus()
    }

    override fun onMicrophoneStarted(id: AudioStreamId, config: MicrophoneConfig) {
        if (closed.get()) return
        microphones.remove(id)?.close()
        CrvApi19MicrophoneUplink(config, report).also { uplink ->
            if (uplink.start()) microphones[id] = uplink else uplink.close()
        }
    }

    override fun onMicrophoneStopped(id: AudioStreamId) {
        microphones.remove(id)?.close()
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        val decoders = synchronized(videoLock) {
            outputSurface = null
            videoDecoders.values.toList().also { videoDecoders.clear() }
        }
        decoders.forEach { it.close() }
        audioRenderers.values.toList().forEach { it.close() }
        audioRenderers.clear()
        abandonAudioFocus()
        microphones.values.toList().forEach { it.close() }
        microphones.clear()
        recoveryHandlers.clear()
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
        }
    }

    @Suppress("DEPRECATION")
    private fun abandonAudioFocus() {
        if (!audioFocusHeld.compareAndSet(true, false)) return
        runCatching { audioManager.abandonAudioFocus(audioFocusListener) }
    }

    private fun decoder(type: Int): LegacyVideoDecoder? =
        synchronized(videoLock) {
            if (closed.get()) return@synchronized null
            videoDecoders[type]?.let { return@synchronized it }
            val surface = outputSurface ?: return@synchronized null
            LegacyVideoDecoder(
                surface = surface,
                width = videoWidth,
                height = videoHeight,
                requestKeyFrame = { recoveryHandlers[type]?.invoke() },
                diagnostic = { line ->
                    diagnosticHandlers[type]?.invoke(line)
                    report("Video: $line")
                },
            ).also { next ->
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
    ) : Closeable {
        private sealed class Job {
            data class Config(val codec: VideoCodec, val data: ByteArray) : Job()
            data class Frame(val data: ByteArray) : Job()
            data class Recover(val reason: String) : Job()
        }

        private val queue = LinkedBlockingDeque<Job>(VIDEO_QUEUE_CAPACITY)
        private val running = AtomicBoolean(true)
        private val thread = Thread(::run, "crv-api19-video").apply {
            isDaemon = true
            start()
        }

        private var decoder: MediaCodec? = null
        private val outputInfo = MediaCodec.BufferInfo()
        private var lastCodec = VideoCodec.H264
        private var lastConfig: ByteArray? = null
        private var waitingForKeyFrame = true
        private var firstRendered = false
        private var lastKeyFrameRequestNs = 0L

        fun configure(codec: VideoCodec, data: ByteArray) {
            queue.offer(Job.Config(codec, data.copyOf()))
        }

        fun submit(data: ByteArray) {
            if (queue.offerLast(Job.Frame(data.copyOf()))) return
            val pendingConfig = queue.asSequence()
                .filterIsInstance<Job.Config>()
                .lastOrNull()
            queue.clear()
            pendingConfig?.let { queue.offerLast(it) }
            queue.offerLast(Job.Recover("video queue overflow"))
        }

        override fun close() {
            if (!running.compareAndSet(true, false)) return
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
                // Best-effort: OEM firmware can reject priority changes; decoding must continue.
                runCatching { Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_DISPLAY) }
                while (running.get()) {
                    when (val job = queue.pollFirst(20, TimeUnit.MILLISECONDS)) {
                        is Job.Config -> configureNow(job.codec, job.data)
                        is Job.Frame -> feed(job.data)
                        is Job.Recover -> recover(job.reason, preferFlush = true)
                        null -> Unit
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
                MediaCodec.createDecoderByType("video/avc").also { codec ->
                    codec.configure(format, surface, null, 0)
                    codec.start()
                    diagnostic("API17 H.264 decoder ready")
                }
            } catch (error: Exception) {
                diagnostic("H.264 decoder failed: ${error.javaClass.simpleName}")
                null
            }
            requestKeyFrameIfDue()
        }

        private fun feed(packet: ByteArray) {
            val config = lastConfig ?: return
            if (decoder == null) configureNow(lastCodec, config)
            val codec = decoder ?: return

            val annexB = MediaCodecSupport.toAnnexB(packet)
            if (annexB.isEmpty()) return

            if (waitingForKeyFrame) {
                if (!MediaCodecSupport.isRandomAccess(annexB, lastCodec)) {
                    requestKeyFrameIfDue()
                    return
                }
                waitingForKeyFrame = false
            }

            try {
                val index = codec.dequeueInputBuffer(VIDEO_INPUT_TIMEOUT_US)
                if (index < 0) {
                    if (!queue.offerFirst(Job.Frame(packet))) {
                        queue.clear()
                        queue.offerFirst(Job.Recover("video decoder input backpressure"))
                    }
                    return
                }
                @Suppress("DEPRECATION")
                val input = codec.inputBuffers[index]
                input.clear()
                if (annexB.size > input.remaining()) {
                    codec.queueInputBuffer(index, 0, 0, 0L, 0)
                    recover("video frame exceeds decoder input")
                    return
                }
                input.put(annexB)
                codec.queueInputBuffer(
                    index,
                    0,
                    annexB.size,
                    System.nanoTime() / 1000L,
                    0,
                )
            } catch (error: Exception) {
                recover("video input failed: ${error.javaClass.simpleName}")
            }
        }

        private fun drain() {
            val codec = decoder ?: return
            try {
                while (running.get()) {
                    val index = codec.dequeueOutputBuffer(outputInfo, 0L)
                    when {
                        index == MediaCodec.INFO_TRY_AGAIN_LATER -> return
                        index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> Unit
                        index == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED -> Unit
                        index >= 0 -> {
                            codec.releaseOutputBuffer(index, true)
                            if (!firstRendered) {
                                firstRendered = true
                                diagnostic("first frame rendered")
                            }
                        }
                        else -> return
                    }
                }
            } catch (error: Exception) {
                recover("video output failed: ${error.javaClass.simpleName}")
            }
        }

        private fun recover(reason: String, preferFlush: Boolean = false) {
            diagnostic(reason)
            val active = decoder
            if (preferFlush && active != null) {
                // Queue overflow is recoverable without destroying the hardware decoder.
                // The next input must still be an IDR/key frame after flush.
                val flushed = runCatching { active.flush() }
                    .onFailure { diagnostic("video decoder flush failed: " + it.javaClass.simpleName) }
                    .isSuccess
                if (flushed) {
                    waitingForKeyFrame = true
                    diagnostic("video decoder flushed; requesting keyframe")
                    requestKeyFrameIfDue()
                    return
                }
            }
            releaseDecoder()
            waitingForKeyFrame = true
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
            val codec = decoder ?: return
            decoder = null
            runCatching { codec.stop() }
            runCatching { codec.release() }
        }
    }

    private class LegacyAudioRenderer(
        private val format: AudioFormat,
        private val report: (String) -> Unit,
    ) : Closeable {
        private data class Packet(val rtp: ByteArray, val sample: Int)

        private val queue = LinkedBlockingDeque<Packet>(AUDIO_QUEUE_CAPACITY)
        private val running = AtomicBoolean(true)
        private val thread = Thread(::run, "crv-api19-audio").apply {
            isDaemon = true
        }

        private var started = false
        private var decoder: MediaCodec? = null
        private val outputInfo = MediaCodec.BufferInfo()
        private var softwareOpus: CrvSoftwareOpusDecoder? = null
        private var rejectedOpusPackets = 0
        private var pcmScratch = ByteArray(0)
        private var track: AudioTrack? = null
        private var playbackStarted = false
        private var primedBytes = 0
        private var primeTargetBytes = 0

        fun start() {
            if (started) return
            started = true
            thread.start()
        }

        fun submit(rtp: ByteArray, sample: Int) {
            if (!running.get()) return
            if (!queue.offerLast(Packet(rtp.copyOf(), sample))) {
                queue.pollFirst()
                queue.offerLast(Packet(rtp.copyOf(), sample))
            }
        }

        override fun close() {
            if (!running.compareAndSet(true, false)) return
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
                    queue.pollFirst(20, TimeUnit.MILLISECONDS)?.let(::handle)
                    drainDecoder()
                }
            } catch (_: InterruptedException) {
                // Normal shutdown.
            } catch (error: Exception) {
                report("Audio renderer failed: ${error.javaClass.simpleName}")
            } finally {
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

            val bufferBytes = maxOf(
                minimum * 2,
                format.sampleRate * format.channels * 2 / 5,
            )
            @Suppress("DEPRECATION")
            val audio = AudioTrack(
                AudioManager.STREAM_MUSIC,
                format.sampleRate,
                channelMask,
                encoding,
                bufferBytes,
                AudioTrack.MODE_STREAM,
            )
            if (audio.state != AudioTrack.STATE_INITIALIZED) {
                audio.release()
                report("AudioTrack failed to initialize")
                return
            }
            track = audio
            primeTargetBytes = minOf(
                bufferBytes / 2,
                format.sampleRate * format.channels * 2 * AUDIO_PRIME_MILLIS / 1000,
            ).coerceAtLeast(format.channels * 2)
            report("Audio API17 ready: ${format.codec} ${format.sampleRate}Hz")
        }

        private fun configureDecoder() {
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
                    candidate
                } catch (error: Exception) {
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
            if (format.codec != AudioCodecKind.OPUS) return false
            if (softwareOpus != null) return true
            decoder?.let { codec ->
                runCatching { codec.stop() }
                runCatching { codec.release() }
            }
            decoder = null
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

        private fun handle(packet: Packet) {
            val payload = CrvLegacyRtp.payload(packet.rtp) ?: return
            when (format.codec) {
                AudioCodecKind.LPCM -> {
                    val pcm = payload.copyOf()
                    var index = 0
                    while (index + 1 < pcm.size) {
                        val first = pcm[index]
                        pcm[index] = pcm[index + 1]
                        pcm[index + 1] = first
                        index += 2
                    }
                    writePcm(pcm)
                }

                AudioCodecKind.AAC_LC -> {
                    if (payload.isNotEmpty()) {
                        feedDecoder(
                            MediaCodecSupport.adtsFrame(
                                payload,
                                format.sampleRate,
                                format.channels,
                            ),
                            sampleTimestampUs(packet.sample),
                        )
                    }
                }

                AudioCodecKind.OPUS -> {
                    if (payload.isNotEmpty()) {
                        val software = softwareOpus
                        if (software != null) {
                            try {
                                val decodedBytes = software.decode(payload)
                                writePcm(software.pcm, decodedBytes)
                            } catch (error: Exception) {
                                rejectedOpusPackets++
                                if (rejectedOpusPackets == 1 || rejectedOpusPackets % 100 == 0) {
                                    report("Opus packet rejected count=" + rejectedOpusPackets +
                                        " type=" + error.javaClass.simpleName)
                                }
                            }
                        } else {
                            feedDecoder(payload, sampleTimestampUs(packet.sample))
                        }
                    }
                }
            }
        }

        private fun feedDecoder(bytes: ByteArray, timestampUs: Long) {
            val codec = decoder ?: return
            try {
                val index = codec.dequeueInputBuffer(AUDIO_INPUT_TIMEOUT_US)
                if (index < 0) return
                @Suppress("DEPRECATION")
                val input = codec.inputBuffers[index]
                input.clear()
                if (bytes.size > input.remaining()) {
                    codec.queueInputBuffer(index, 0, 0, 0L, 0)
                    return
                }
                input.put(bytes)
                codec.queueInputBuffer(index, 0, bytes.size, timestampUs, 0)
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
                        index == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED -> Unit
                        index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> Unit
                        index >= 0 -> {
                            if (outputInfo.size > 0) {
                                @Suppress("DEPRECATION")
                                val output = codec.outputBuffers[index]
                                if (pcmScratch.size < outputInfo.size) pcmScratch = ByteArray(outputInfo.size)
                                output.position(outputInfo.offset)
                                output.limit(outputInfo.offset + outputInfo.size)
                                output.get(pcmScratch, 0, outputInfo.size)
                                writePcm(pcmScratch, outputInfo.size)
                            }
                            codec.releaseOutputBuffer(index, false)
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

        private fun writePcm(bytes: ByteArray, byteCount: Int = bytes.size) {
            val audio = track ?: return
            var offset = 0
            while (offset < byteCount && running.get()) {
                @Suppress("DEPRECATION")
                val written = audio.write(bytes, offset, byteCount - offset)
                if (written <= 0) return
                offset += written
                if (!playbackStarted) {
                    primedBytes += written
                    if (primedBytes >= primeTargetBytes) {
                        audio.play()
                        playbackStarted = true
                    }
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
            decoder?.let { codec ->
                runCatching { codec.stop() }
                runCatching { codec.release() }
            }
            decoder = null
            softwareOpus = null

            track?.let { audio ->
                runCatching { audio.stop() }
                runCatching { audio.release() }
            }
            track = null
        }
    }

    private companion object {
        const val VIDEO_QUEUE_CAPACITY = 8
        const val AUDIO_QUEUE_CAPACITY = 96
        const val AUDIO_PRIME_MILLIS = 30
        const val MAX_VIDEO_INPUT = 8 * 1024 * 1024
        const val VIDEO_INPUT_TIMEOUT_US = 2_000L
        const val AUDIO_INPUT_TIMEOUT_US = 10_000L
        const val AAC_LC_OBJECT_TYPE = 2
        const val KEYFRAME_INTERVAL_NS = 1_000_000_000L
        const val WORKER_CLOSE_JOIN_MILLIS = 750L
        val START_CODE = byteArrayOf(0x00, 0x00, 0x00, 0x01)
    }
}
