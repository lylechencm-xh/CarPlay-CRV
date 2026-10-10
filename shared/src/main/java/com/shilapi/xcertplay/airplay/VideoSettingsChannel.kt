package com.shilapi.xcertplay.airplay

import java.io.Closeable
import java.io.BufferedInputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The "CarPlayVideo Settings App" data stream (type 130, [VideoInCar.SETTINGS_CHANNEL_UUID]) the iPhone
 * opens once video in car is declared; it tears the session down when this stream is refused. Framing
 * as the iAP tunnel: ChaCha20-Poly1305 frames with a 2-byte little-endian length, carrying packages
 * with a 32-byte header (size, 12-byte type such as "sync"/"rply", 4-byte command, 8-byte sequence
 * number) and a body. Each "sync" is answered with an empty "rply", as AirPlay data streams expect;
 * the bodies are not needed to play.
 */
class VideoSettingsChannel(
    private val readKey: ByteArray,
    private val writeKey: ByteArray,
    private val log: (String) -> Unit,
) : Closeable {
    private val closed = AtomicBoolean(false)
    private var server: ServerSocket? = null
    private var socket: Socket? = null
    private var writeCounter = 0L

    fun listen(bindAddress: InetAddress): Int {
        val bound = ServerSocket().apply {
            reuseAddress = true
            bind(InetSocketAddress(bindAddress, 0))
        }
        server = bound
        Thread({ accept(bound) }, "diplay-video-settings").apply { isDaemon = true }.start()
        return bound.localPort
    }

    private fun accept(bound: ServerSocket) {
        try {
            val accepted = bound.accept()
            socket = accepted
            if (closed.get()) {
                accepted.close()
                return
            }
            runCatching { accepted.tcpNoDelay = true }
            log("video settings channel connected")
            run(accepted)
        } catch (error: Exception) {
            if (!closed.get()) log("video settings channel ended: ${error.message}")
        } finally {
            close()
        }
    }

    private fun run(sock: Socket) {
        val input = BufferedInputStream(sock.getInputStream(), 16 * 1024)
        val output = sock.getOutputStream()
        val opener = AirPlayChaChaOpener(readKey)
        val nonce = ByteArray(12)
        val frames = AirPlayDataStreamReader { counter, aad, sealed, length ->
            opener.open(AirPlayCrypto.nonce64(counter, nonce), sealed, length = length, aad = aad)
        }
        val packages = AirPlayPackageBuffer(MAX_PACKAGE)
        while (!closed.get()) {
            val plaintext = frames.read(input) ?: break
            packages.append(plaintext) { buffer, _ ->
                if (ascii(buffer, 4, 12) == "sync") reply(output, buffer.copyOfRange(20, 28))
            }
        }
        if (!closed.get()) packages.finish()
    }

    private fun reply(output: OutputStream, seq: ByteArray) {
        val header = ByteArray(HEADER)
        header[3] = HEADER.toByte()
        "rply".toByteArray(Charsets.US_ASCII).copyInto(header, 4)
        seq.copyInto(header, 20)
        val aad = byteArrayOf((header.size and 0xff).toByte(), (header.size shr 8).toByte())
        val sealed = AirPlayCrypto.chachaSeal(writeKey, AirPlayCrypto.nonce64(writeCounter++), header, aad)
        output.write(aad + sealed)
        output.flush()
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        runCatching { socket?.close() }
        runCatching { server?.close() }
    }

    private fun ascii(b: ByteArray, o: Int, n: Int) =
        String(b.copyOfRange(o, o + n), Charsets.US_ASCII).trimEnd('\u0000')

    private companion object {
        const val HEADER = 32
        const val MAX_PACKAGE = 8 * 1024 * 1024
    }
}
