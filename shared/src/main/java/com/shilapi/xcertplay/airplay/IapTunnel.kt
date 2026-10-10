package com.shilapi.xcertplay.airplay

import java.io.Closeable
import java.io.BufferedInputStream
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Receive-only iAP2-over-CarPlay DataStream tunnel (stream type 130).
 *
 * The TCP stream is NetSocketChaCha20Poly1305 framed, then carries APTransportPackage records.
 * iAP2 bodies (messageType "comm") are emitted verbatim for the wired iAP2 relay.
 */
class IapTunnel(
    private val readKey: ByteArray,
    bindAddress: InetAddress = InetAddress.getByName("0.0.0.0"),
) : Closeable {
    interface Listener {
        fun onOpen(remoteAddress: String?) {}
        fun onIap(bytes: ByteArray) {}
        fun onDebug(message: String) {}
        fun onClosed(cause: Throwable?) {}
    }

    private val closed = AtomicBoolean(false)
    private val bindAddress =
        if (bindAddress is Inet4Address) InetAddress.getByName("0.0.0.0") else bindAddress
    private val servers = mutableListOf<ServerSocket>()
    private var socket: Socket? = null
    private val threads = mutableListOf<Thread>()
    private val peerConnected = CountDownLatch(1)
    @Volatile private var listener: Listener = object : Listener {}

    fun listen(listener: Listener): Int {
        this.listener = listener
        val bound = bindAny()
        servers += bound
        listener.onDebug("AirPlay iAP tunnel listener bound=${bound.localSocketAddress}")
        val secondaryAddress = if (bindAddress is java.net.Inet6Address) {
            InetAddress.getByName("0.0.0.0")
        } else {
            InetAddress.getByName("::")
        }
        val secondary = ServerSocket()
        runCatching {
            secondary.apply {
                reuseAddress = true
                bind(InetSocketAddress(secondaryAddress, bound.localPort))
            }
        }.onSuccess { secondary ->
            servers += secondary
            listener.onDebug(
                "AirPlay iAP tunnel secondary listener bound=" +
                    "${secondary.localSocketAddress}",
            )
        }.onFailure { error ->
            closeServerSocket(secondary)
            listener.onDebug(
                "AirPlay iAP tunnel secondary listener failed address=" +
                    "$secondaryAddress port=${bound.localPort}: ${error.message}",
            )
        }
        servers.forEach { server ->
            threads += Thread({ accept(server) }, "airplay-iap-tunnel").apply {
                isDaemon = true
                start()
            }
        }
        return bound.localPort
    }

    private fun bindAny(): ServerSocket =
        ServerSocket().apply {
            reuseAddress = true
            bind(InetSocketAddress(bindAddress, 0))
        }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        closeSocket(socket)
        servers.toList().forEach(::closeServerSocket)
        servers.clear()
        threads.toList().forEach(Thread::interrupt)
        threads.clear()
        peerConnected.countDown()
    }

    /** Waits until the iPhone has connected to the advertised dataPort. */
    fun awaitPeerConnection(timeoutMillis: Long): Boolean {
        require(timeoutMillis >= 0) { "timeoutMillis must not be negative" }
        return try {
            peerConnected.await(timeoutMillis, TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
    }

    private fun accept(bound: ServerSocket) {
        listener.onDebug(
            "AirPlay iAP tunnel accepting local=${bound.localSocketAddress}",
        )
        while (!closed.get()) {
            val accepted = try {
                bound.accept()
            } catch (error: Exception) {
                if (!closed.get()) listener.onClosed(error)
                return
            }
            if (closed.get()) {
                closeSocket(accepted)
                return
            }
            accepted.setSoLinger(true, 0)
            socket = accepted
            peerConnected.countDown()
            listener.onOpen(accepted.remoteSocketAddress?.toString())
            run(accepted)
        }
    }

    private fun run(sock: Socket) {
        val opener = AirPlayChaChaOpener(readKey)
        val nonce = ByteArray(12)
        val frames = AirPlayDataStreamReader { counter, aad, sealed, length ->
            opener.open(AirPlayCrypto.nonce64(counter, nonce), sealed, length = length, aad = aad)
        }
        val packages = AirPlayPackageBuffer(MAX_PACKAGE)
        var failure: Throwable? = null
        var announcedData = false
        try {
            val input = BufferedInputStream(sock.getInputStream(), READ_CHUNK_BYTES)
            while (!closed.get()) {
                val plaintext = frames.read(input) ?: break
                if (!announcedData) {
                    announcedData = true
                    listener.onDebug("AirPlay iAP tunnel received data")
                }
                packages.append(plaintext) { buffer, size ->
                    if (readU32Be(buffer, MESSAGE_TYPE_OFFSET) == MSG_TYPE_COMM) {
                        listener.onDebug("AirPlay iAP tunnel package type=comm body=${size - PACKAGE_HEADER_LEN}")
                        listener.onIap(buffer.copyOfRange(PACKAGE_HEADER_LEN, size))
                    }
                }
            }
            if (!closed.get()) {
                packages.finish()
                listener.onDebug("AirPlay iAP tunnel peer EOF")
            }
        } catch (error: Exception) {
            failure = error
        } finally {
            if (socket === sock) socket = null
            closeSocket(sock)
            if (!closed.get() && failure != null) listener.onClosed(failure)
        }
    }

    private fun closeSocket(value: Socket?) {
        try { value?.close() } catch (_: Exception) { }
    }

    private fun closeServerSocket(value: ServerSocket?) {
        try { value?.close() } catch (_: Exception) { }
    }

    private fun readU32Be(source: ByteArray, offset: Int): Int =
        ((source[offset].toInt() and 0xff) shl 24) or
            ((source[offset + 1].toInt() and 0xff) shl 16) or
            ((source[offset + 2].toInt() and 0xff) shl 8) or
            (source[offset + 3].toInt() and 0xff)

    private companion object {
        const val PACKAGE_HEADER_LEN = 32
        const val MESSAGE_TYPE_OFFSET = 16
        const val MSG_TYPE_COMM = 0x636f6d6d
        const val MAX_PACKAGE = 4 * 1024 * 1024
        const val READ_CHUNK_BYTES = 16 * 1024
    }
}
