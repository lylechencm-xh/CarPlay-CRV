package com.shilapi.xcertplay.airplay

import android.util.Log
import android.os.Process
import android.os.Debug
import java.io.Closeable
import java.io.InputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

enum class VideoCodec { H264, H265 }

/**
 * Receives one CarPlay screen stream on a TCP data port.
 *
 * Each message is a 128-byte AirPlayScreenHeader followed by a body: a clear VideoConfig
 * (avcC/hvcC) or a ChaCha20-Poly1305 sealed VideoFrame. The key is the DataStream output key
 * and the per-frame nonce is an 8-byte little-endian counter.
 */
class ScreenStream(private val key: ByteArray, private val onDiagnostic: (String) -> Unit = {}) : Closeable {
    interface Listener {
        fun isVideoVisible(): Boolean = true
        fun wantsLengthPrefixedFrames(): Boolean = false
        fun onCodec(codec: VideoCodec) {}
        fun onConfig(codecData: ByteArray) {}
        fun onFrame(naluBytes: ByteArray) {}
        fun onFrame(naluBytes: ByteArray, arrivalNs: Long) = onFrame(naluBytes)
        fun onDiscontinuity() {}
        fun onClosed(cause: Throwable?) {}
    }

    private val opener = AirPlayChaChaOpener(key)
    private val frameNonce = ByteArray(12)
    private val closed = AtomicBoolean(false)
    private val frameCounter = AtomicLong(0)
    private val firstFrameLogged = AtomicBoolean(false)
    private var server: ServerSocket? = null
    private var socket: Socket? = null
    private var thread: Thread? = null
    private var decryptThread: Thread? = null
    private val receiveQueue = ScreenReceiveQueue()
    private val notifiedClosed = AtomicBoolean(false)
    @Volatile private var receiveFailure: Throwable? = null
    @Volatile private var decryptTid = 0
    @Volatile private var receiveTid = 0
    @Volatile private var listener: Listener = object : Listener {}
    private val visibility = ScreenVisibilityEpoch()
    private val hiddenFrames = AtomicLong()

    /** May be called from a visibility callback while a large frame is still decrypting. */
    fun refreshVideoPriority() {
        val visible = visibility.refresh { listener.isVideoVisible() }
        for (tid in intArrayOf(receiveTid, decryptTid)) {
            if (tid == 0) continue
            runCatching {
                // Keep draining TCP during the OEM camera overlay. Only expensive processing
                // becomes background work; otherwise old frames accumulate outside our queue.
                Process.setThreadPriority(tid, if (tid == receiveTid || visible)
                    Process.THREAD_PRIORITY_DISPLAY else Process.THREAD_PRIORITY_BACKGROUND)
            }
        }
    }

    fun listen(bindAddress: InetAddress, listener: Listener): Int {
        this.listener = listener
        val bound = ServerSocket()
        bound.reuseAddress = true
        // A receive window above 64 KiB must be requested before bind/accept.
        // Setting only the accepted Socket can report a large buffer while the
        // peer is still constrained by the smaller negotiated TCP window.
        runCatching { bound.receiveBufferSize = VIDEO_RECEIVE_BUFFER_BYTES }
        bound.bind(InetSocketAddress(bindAddress, 0))
        server = bound
        decryptThread = Thread({ decryptLoop() }, "airplay-screen-decrypt").apply { isDaemon = true; start() }
        thread = Thread({ accept(bound) }, "airplay-screen").apply { isDaemon = true; start() }
        return bound.localPort
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        closeSocket(socket)
        closeServerSocket(server)
        thread?.interrupt()
        receiveQueue.close()
        decryptThread?.interrupt()
    }

    private fun accept(bound: ServerSocket) {
        try {
            val accepted = bound.accept()
            socket = accepted
            // H52 P16-inspired tuning; nonfatal if the OEM TCP stack rejects a hint.
            runCatching { accepted.tcpNoDelay = true }
            runCatching { accepted.receiveBufferSize = VIDEO_RECEIVE_BUFFER_BYTES }
            onDiagnostic("video tcp receiveBuffer=" +
                runCatching { accepted.receiveBufferSize }.getOrDefault(0) +
                " noDelay=" + runCatching { accepted.tcpNoDelay }.getOrDefault(false))
            run(accepted)
        } catch (error: Exception) {
            receiveFailure = error
            receiveQueue.finish()
        }
    }

    private fun run(sock: Socket) {
        receiveTid = Process.myTid()
        refreshVideoPriority()
        var failure: Throwable? = null
        val stats = StreamReceiveStats("video-receive", onDiagnostic)
        try {
            val input = sock.getInputStream()
            val header = ByteArray(HEADER_LEN)
            var bodyBuffer = ByteArray(INITIAL_BODY_BUFFER_BYTES)
            var visible: Boolean? = null
            while (!closed.get()) {
                val nextVisible = listener.isVideoVisible()
                if (visible != nextVisible) {
                    visible = nextVisible
                    refreshVideoPriority()
                    onDiagnostic("video receive visible=$nextVisible")
                }
                stats.reading()
                if (!readFully(input, header)) break
                val bodySize = readU32Le(header, 0)
                if (bodySize < 0 || bodySize > MAX_BODY) break
                if (bodySize > bodyBuffer.size) {
                    bodyBuffer = ByteArray(growBodyBuffer(bodySize))
                }
                if (!readFully(input, bodyBuffer, bodySize)) break
                stats.received(HEADER_LEN + bodySize)
                val arrivalNs = System.nanoTime()
                val encrypted = header[OPCODE_OFFSET].toInt() and 0xff == OP_VIDEO_FRAME && bodySize >= ScreenCodec.TAG_SIZE
                val nonce = if (encrypted) frameCounter.getAndIncrement() else 0L
                // Each image has an independent AEAD nonce, assigned BEFORE optional discard.
                // Hidden ciphertext is never parsed or rendered. Config remains ordered.
                if (header[OPCODE_OFFSET].toInt() and 0xff == OP_VIDEO_FRAME && !listener.isVideoVisible()) {
                    hiddenFrames.incrementAndGet()
                } else if (!receiveQueue.offer(header, bodyBuffer, bodySize, arrivalNs, nonce,
                        visibility.epoch)) break
                stats.processed()
            }
        } catch (error: Exception) {
            failure = error
        } finally {
            receiveTid = 0
            onDiagnostic("video receive hiddenSkippedTotal=${hiddenFrames.get()} ended=true")
            stats.flush(ended = true)
            if (socket === sock) socket = null
            closeSocket(sock)
            receiveFailure = failure
            receiveQueue.finish()
        }
    }

    private fun decryptLoop() {
        decryptTid = Process.myTid()
        refreshVideoPriority()
        val stats = StreamReceiveStats("video-processing", onDiagnostic)
        val runtimeDiagnostics = MediaWorkerDiagnostics({ decryptTid }, { listener.isVideoVisible() }, onDiagnostic)
        runtimeDiagnostics.start()
        var failure: Throwable? = null
        try {
            while (!closed.get()) {
                val packet = receiveQueue.take() ?: break
                try {
                    stats.reading()
                    stats.received(packet.length + HEADER_LEN)
                    onMessage(packet.header, packet.body, packet.length, stats, packet.arrivalNs,
                        packet.nonce, packet.visibilityEpoch)
                    stats.processed()
                } finally { receiveQueue.recycle(packet) }
            }
        } catch (_: InterruptedException) {
            // Explicit stream retirement closes both workers without touching its successor.
        } catch (error: Exception) {
            failure = error
        } finally {
            runtimeDiagnostics.close()
            decryptTid = 0
            receiveQueue.close()
            closeSocket(socket)
            stats.flush(ended = true)
            if (!closed.get() && notifiedClosed.compareAndSet(false, true)) {
                listener.onClosed(failure ?: receiveFailure)
            }
        }
    }

    private val decryptTimes = LatencyHistogram()
    private val queueTimes = LatencyHistogram()
    private var timingAtNs = System.nanoTime()
    private var agedFrames = 0L

    private fun onMessage(header: ByteArray, body: ByteArray, bodySize: Int, stats: StreamReceiveStats,
                          arrivalNs: Long, nonce: Long, epoch: Long) {
        when (header[OPCODE_OFFSET].toInt() and 0xff) {
            OP_VIDEO_FRAME -> {
                if (!visibility.accepts(epoch) || !listener.isVideoVisible()) {
                    hiddenFrames.incrementAndGet()
                    return
                }
                val decryptStartNs = System.nanoTime()
                val decryptCpuStartNs = Debug.threadCpuTimeNanos()
                val payload = if (bodySize >= ScreenCodec.TAG_SIZE) {
                    opener.open(
                        nonce = AirPlayCrypto.nonce64(nonce, frameNonce),
                        source = body,
                        offset = 0,
                        length = bodySize,
                        aad = header,
                    )
                } else {
                    body.copyOf(bodySize)
                }
                val decryptedNs = System.nanoTime()
                val decryptCpuNs = (Debug.threadCpuTimeNanos() - decryptCpuStartNs).coerceAtLeast(0L)
                decryptTimes.record(decryptedNs - decryptStartNs)
                queueTimes.record(decryptStartNs - arrivalNs)
                if (decryptedNs - arrivalNs > FRAME_AGE_WARNING_NS) agedFrames++
                if (decryptedNs - timingAtNs >= 5_000_000_000L) {
                    onDiagnostic("video pipeline decryptP50Ms=${decryptTimes.percentileMs(50)} " +
                        "decryptP90Ms=${decryptTimes.percentileMs(90)} decryptP99Ms=${decryptTimes.percentileMs(99)} " +
                        "queueP90Ms=${queueTimes.percentileMs(90)} agedFrames=$agedFrames " +
                        "hiddenSkippedTotal=${hiddenFrames.get()} receiveAgePolicy=observe-only")
                    decryptTimes.reset(); queueTimes.reset(); timingAtNs = decryptedNs
                }
                if (firstFrameLogged.compareAndSet(false, true)) {
                    Log.i(
                        TAG,
                        "video first decrypted frame sealed=$bodySize plain=${payload.size} " +
                            "head=${payload.hexPrefix(16)}",
                    )
                }
                if (!visibility.accepts(epoch) || !listener.isVideoVisible()) {
                    stats.videoStages(decryptedNs - decryptStartNs, 0L, 0L, payload.size, decryptCpuNs)
                    return
                }
                // Receive age includes authentication time and FIFO backpressure, not just
                // decoder backlog. A steady slow stream can exceed 250ms on every frame.
                // Preserve its reference chain instead of repeatedly flushing on each IDR's
                // successor. The sink owns bounded input queues and output-stall recovery;
                // it can discard decoded output without losing compressed references.
                val convertStartNs = System.nanoTime()
                val frame = ScreenCodec.prepareFrame(payload, listener.wantsLengthPrefixedFrames())
                val convertedNs = System.nanoTime()
                listener.onFrame(frame, arrivalNs)
                stats.videoStages(decryptedNs - decryptStartNs, convertedNs - convertStartNs,
                    System.nanoTime() - convertedNs, frame.size, decryptCpuNs)
            }
            OP_VIDEO_CONFIG -> {
                val configBody = body.copyOf(bodySize)
                val (codec, codecData) = ScreenCodec.detectConfig(configBody)
                Log.i(TAG, "video codec config codec=$codec body=$bodySize data=${codecData.size}")
                listener.onCodec(codec)
                listener.onConfig(codecData)
            }
        }
    }

    private fun closeSocket(value: Socket?) {
        try { value?.close() } catch (_: Exception) { }
    }

    private fun closeServerSocket(value: ServerSocket?) {
        try { value?.close() } catch (_: Exception) { }
    }

    private fun readFully(input: InputStream, output: ByteArray): Boolean =
        readFully(input, output, output.size)

    private fun readFully(input: InputStream, output: ByteArray, length: Int): Boolean {
        if (length < 0 || length > output.size) return false
        var offset = 0
        while (offset < length) {
            val read = input.read(output, offset, length - offset)
            if (read < 0) return false
            if (read == 0) continue
            offset += read
        }
        return true
    }

    private fun growBodyBuffer(required: Int): Int {
        var size = INITIAL_BODY_BUFFER_BYTES
        while (size < required && size < MAX_BODY) {
            size = minOf(MAX_BODY, size * 2)
        }
        return maxOf(required, size)
    }

    private companion object {
        const val TAG = "xcertplay-usb"
        const val HEADER_LEN = 128
        const val OPCODE_OFFSET = 4
        const val OP_VIDEO_FRAME = 0
        const val OP_VIDEO_CONFIG = 1
        const val INITIAL_BODY_BUFFER_BYTES = 256 * 1024
        const val MAX_BODY = 8 * 1024 * 1024
        const val VIDEO_RECEIVE_BUFFER_BYTES = 512 * 1024
        const val FRAME_AGE_WARNING_NS = 250_000_000L
    }
}

/** A hide/show round trip invalidates queued pictures even if processing was blocked throughout. */
internal class ScreenVisibilityEpoch {
    @Volatile var epoch = 0L
        private set
    @Volatile private var visible = true
    @Synchronized fun refresh(readVisible: () -> Boolean): Boolean {
        // Read under this lock so a delayed receiver cannot overwrite a newer UI transition
        // with a visibility snapshot it took before the camera callback.
        val next = readVisible()
        if (visible != next) { epoch++; visible = next }
        return next
    }
    fun accepts(value: Long): Boolean = visible && value == epoch
}

private fun ByteArray.hexPrefix(length: Int): String =
    take(length).joinToString("") { "%02x".format(it.toInt() and 0xff) }

/** Extracts the avcC/hvcC codec-data record from a VideoConfig payload. */
object ScreenCodec {
    /** Keep the owned decrypted payload intact for sinks that convert directly into codec input. */
    fun prepareFrame(payload: ByteArray, lengthPrefixed: Boolean): ByteArray =
        if (lengthPrefixed) payload else lengthPrefixedToAnnexB(payload)

    fun decryptFrame(key: ByteArray, counter: Long, header: ByteArray, body: ByteArray): ByteArray =
        if (body.size < TAG_SIZE) body
        else AirPlayCrypto.chachaOpen(key, AirPlayCrypto.nonce64(counter), body, header)

    /**
     * Replaces each four-byte NAL length with an Annex B start code in place.
     *
     * The payload is left untouched unless every length-prefixed NAL is valid, so malformed
     * input keeps its original bytes for the normal decoder error path.
     */
    fun lengthPrefixedToAnnexB(payload: ByteArray): ByteArray {
        if (payload.size < 4 || payload.startsWithStartCode()) return payload

        var offset = 0
        while (offset + 4 <= payload.size) {
            val length = readU32Be(payload, offset)
            offset += 4
            if (length <= 0 || offset + length > payload.size) return payload
            offset += length
        }
        if (offset != payload.size) return payload

        offset = 0
        while (offset + 4 <= payload.size) {
            val length = readU32Be(payload, offset)
            payload[offset] = 0
            payload[offset + 1] = 0
            payload[offset + 2] = 0
            payload[offset + 3] = 1
            offset += 4 + length
        }
        return payload
    }

    fun detectConfig(payload: ByteArray): Pair<VideoCodec, ByteArray> {
        for (index in 4..payload.size - 4) {
            val fourcc = String(payload, index, 4, Charsets.US_ASCII)
            when (fourcc) {
                "hvcC" -> return VideoCodec.H265 to payload.copyOfRange(index + 4, payload.size)
                "avcC" -> return VideoCodec.H264 to payload.copyOfRange(index + 4, payload.size)
            }
        }
        return if (looksLikeAvcC(payload)) VideoCodec.H264 to payload else VideoCodec.H265 to payload
    }

    private fun looksLikeAvcC(payload: ByteArray): Boolean {
        if (payload.size < 9) return false
        if ((payload[5].toInt() and 0x1f) < 1) return false
        val spsLength = readU16Be(payload, 6)
        if (8 + spsLength > payload.size) return false
        return (payload[8].toInt() and 0x1f) == 7
    }

    private fun readU16Be(source: ByteArray, offset: Int): Int =
        ((source[offset].toInt() and 0xff) shl 8) or (source[offset + 1].toInt() and 0xff)

    const val TAG_SIZE = 16
}

private fun ByteArray.startsWithStartCode(): Boolean =
    size >= 4 &&
        this[0] == 0.toByte() &&
        this[1] == 0.toByte() &&
        this[2] == 0.toByte() &&
        this[3] == 1.toByte()

private fun readU32Be(source: ByteArray, offset: Int): Int =
    ((source[offset].toInt() and 0xff) shl 24) or
        ((source[offset + 1].toInt() and 0xff) shl 16) or
        ((source[offset + 2].toInt() and 0xff) shl 8) or
        (source[offset + 3].toInt() and 0xff)

private fun readU32Le(source: ByteArray, offset: Int): Int =
    (source[offset].toInt() and 0xff) or
        ((source[offset + 1].toInt() and 0xff) shl 8) or
        ((source[offset + 2].toInt() and 0xff) shl 16) or
        ((source[offset + 3].toInt() and 0xff) shl 24)
