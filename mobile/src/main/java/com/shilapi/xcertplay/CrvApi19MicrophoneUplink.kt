package com.shilapi.xcertplay

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import com.shilapi.xcertplay.airplay.AudioCodecKind
import com.shilapi.xcertplay.airplay.MicrophoneConfig
import com.shilapi.xcertplay.airplay.MicrophoneCounters
import com.shilapi.xcertplay.airplay.MicrophonePacketizer
import java.io.Closeable
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Android 4.4-safe CarPlay microphone uplink.
 *
 * The CR-V API17 profile advertises PCM input only, so this path deliberately has no Opus,
 * AudioRecord.Builder, routing APIs, or API23 runtime-permission calls.
 */
internal class CrvApi19MicrophoneUplink(
    private val config: MicrophoneConfig,
    private val report: (String) -> Unit,
) : Closeable {
    private val running = AtomicBoolean(false)
    @Volatile private var recorder: AudioRecord? = null
    @Volatile private var socket: DatagramSocket? = null
    private var thread: Thread? = null

    @SuppressLint("MissingPermission")
    fun start(): Boolean {
        if (config.codec != AudioCodecKind.LPCM) {
            report("Microphone: unsupported API17 codec=${config.codec}")
            return false
        }
        if (!running.compareAndSet(false, true)) return true

        val channelMask = if (config.channels >= 2) {
            AudioFormat.CHANNEL_IN_STEREO
        } else {
            AudioFormat.CHANNEL_IN_MONO
        }
        val minimum = AudioRecord.getMinBufferSize(
            config.sampleRate,
            channelMask,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minimum <= 0) {
            report("Microphone: unsupported format rate=${config.sampleRate} channels=${config.channels}")
            running.set(false)
            return false
        }

        val bufferBytes = maxOf(minimum * 2, config.frameBytes * 4)
        val preferredSource = when (config.audioType) {
            "telephony" -> MediaRecorder.AudioSource.VOICE_COMMUNICATION
            "speechrecognition" -> MediaRecorder.AudioSource.VOICE_RECOGNITION
            else -> MediaRecorder.AudioSource.MIC
        }

        val nextRecorder = createRecorder(
            source = preferredSource,
            channelMask = channelMask,
            bufferBytes = bufferBytes,
        ) ?: if (preferredSource != MediaRecorder.AudioSource.MIC) {
            report("Microphone: voice source unavailable; using MIC")
            createRecorder(
                source = MediaRecorder.AudioSource.MIC,
                channelMask = channelMask,
                bufferBytes = bufferBytes,
            )
        } else {
            null
        }

        if (nextRecorder == null) {
            report("Microphone: AudioRecord failed to initialize")
            running.set(false)
            return false
        }

        val bindAddress = if (config.host is Inet4Address) "0.0.0.0" else "::"
        val nextSocket = try {
            DatagramSocket(null).apply {
                reuseAddress = true
                bind(InetSocketAddress(bindAddress, 0))
            }
        } catch (error: Exception) {
            nextRecorder.release()
            running.set(false)
            report("Microphone: UDP socket failed ${error.javaClass.simpleName}")
            return false
        }

        recorder = nextRecorder
        socket = nextSocket
        return try {
            nextRecorder.startRecording()
            if (nextRecorder.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                throw IllegalStateException("AudioRecord did not enter recording state")
            }
            report(
                "Microphone: API17 PCM ready type=${config.audioType} " +
                    "rate=${config.sampleRate} channels=${config.channels}",
            )
            thread = Thread(
                { capture(nextRecorder, nextSocket) },
                "crv-api19-mic",
            ).apply {
                isDaemon = true
                start()
            }
            true
        } catch (error: Exception) {
            report("Microphone: start failed ${error.javaClass.simpleName}")
            releaseResources()
            false
        }
    }

    @SuppressLint("MissingPermission")
    private fun createRecorder(
        source: Int,
        channelMask: Int,
        bufferBytes: Int,
    ): AudioRecord? {
        val next = try {
            @Suppress("DEPRECATION")
            AudioRecord(
                source,
                config.sampleRate,
                channelMask,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferBytes,
            )
        } catch (_: Exception) {
            return null
        }
        if (next.state != AudioRecord.STATE_INITIALIZED) {
            next.release()
            return null
        }
        return next
    }

    private fun capture(record: AudioRecord, udp: DatagramSocket) {
        val frame = ByteArray(config.frameBytes)
        val readBuffer = ByteArray(maxOf(config.frameBytes, MIN_READ_BYTES))
        val counters = MicrophoneCounters()
        var filled = 0
        try {
            while (running.get()) {
                val count = record.read(readBuffer, 0, readBuffer.size)
                if (count < 0) {
                    if (running.get()) report("Microphone: read failed code=$count")
                    return
                }
                if (count == 0) continue

                var offset = 0
                while (offset < count && running.get()) {
                    val copied = minOf(frame.size - filled, count - offset)
                    readBuffer.copyInto(frame, filled, offset, offset + copied)
                    filled += copied
                    offset += copied
                    if (filled == frame.size) {
                        sendFrame(udp, counters, frame)
                        filled = 0
                    }
                }
            }
        } catch (error: Exception) {
            if (running.get()) report("Microphone: capture stopped ${error.javaClass.simpleName}")
        } finally {
            running.set(false)
            releaseResources()
        }
    }

    private fun sendFrame(
        udp: DatagramSocket,
        counters: MicrophoneCounters,
        pcm: ByteArray,
    ) {
        val body = MicrophonePacketizer.toWirePcm(pcm)
        val packet = MicrophonePacketizer.sealPacket(
            key = config.key,
            payloadType = config.payloadType,
            counters = counters,
            body = body,
            samples = config.samplesPerPacket,
        )
        udp.send(DatagramPacket(packet, packet.size, config.host, config.port))
    }

    override fun close() {
        if (running.compareAndSet(true, false)) {
            runCatching { recorder?.stop() }
            runCatching { socket?.close() }
            thread?.let { worker ->
                if (worker !== Thread.currentThread()) {
                    try {
                        worker.join(CLOSE_JOIN_MILLIS)
                    } catch (_: InterruptedException) {
                        Thread.currentThread().interrupt()
                    }
                    if (worker.isAlive) worker.interrupt()
                }
            }
        }
        releaseResources()
    }

    @Synchronized
    private fun releaseResources() {
        running.set(false)
        val currentRecorder = recorder
        recorder = null
        runCatching { currentRecorder?.release() }
        val currentSocket = socket
        socket = null
        runCatching { currentSocket?.close() }
    }

    private companion object {
        const val MIN_READ_BYTES = 2_048
        const val CLOSE_JOIN_MILLIS = 500L
    }
}
