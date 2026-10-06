package com.shilapi.xcertplay.transport

import java.io.Closeable
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.SSLSocket

/**
 * API17-compatible Lockdown TLS over a platform SSLSocket.
 *
 * Android 4.2 exposes TLSv1.2 on SSLSocket but not on SSLEngine. A loopback byte bridge adapts the
 * socket transport to the existing USBMUX byte stream; encrypted TLS records never leave the
 * device except through [underlying].
 */
class TlsSocketDuplexChannel private constructor(
    private val underlying: BlockingDuplexByteStream,
    private val sslSocket: SSLSocket,
    private val bridgeSocket: Socket,
    private val pumpThreads: List<Thread>,
) : BlockingDuplexByteStream {
    private val closed = AtomicBoolean(false)
    private val stateLock = Any()
    private val readLock = Any()
    private val writeLock = Any()
    private var failure: IphoneUsbException? = null

    override fun send(data: ByteArray) = synchronized(writeLock) {
        checkOpen()
        if (data.isEmpty()) return@synchronized
        try {
            sslSocket.outputStream.write(data)
            sslSocket.outputStream.flush()
        } catch (error: IOException) {
            throw fail("TLS socket write failed", error)
        }
    }

    override fun recv(maxBytes: Int, timeoutMillis: Long): ByteArray? = synchronized(readLock) {
        require(maxBytes > 0) { "maxBytes must be positive" }
        require(timeoutMillis > 0) { "timeoutMillis must be positive" }
        checkOpen()
        val timeout = timeoutMillis.coerceAtMost(Int.MAX_VALUE.toLong()).coerceAtLeast(1L).toInt()
        try {
            sslSocket.soTimeout = timeout
            val buffer = ByteArray(maxBytes)
            val count = sslSocket.inputStream.read(buffer)
            return@synchronized when {
                count < 0 -> ByteArray(0)
                count == buffer.size -> buffer
                else -> buffer.copyOf(count)
            }
        } catch (_: SocketTimeoutException) {
            return@synchronized null
        } catch (error: IOException) {
            throw fail("TLS socket read failed", error)
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        closeQuietly(sslSocket)
        closeQuietly(bridgeSocket)
        try {
            underlying.close()
        } catch (_: Exception) {
        }
        pumpThreads.forEach(::join)
    }

    private fun checkOpen() {
        synchronized(stateLock) {
            failure?.let { throw it }
            if (closed.get()) throw IphoneUsbException.DeviceUnavailable("TLS socket channel is closed")
        }
    }

    private fun fail(message: String, cause: Throwable): IphoneUsbException {
        val mapped = IphoneUsbException.DeviceUnavailable(message, cause)
        synchronized(stateLock) {
            if (failure == null) failure = mapped
        }
        close()
        return synchronized(stateLock) { failure ?: mapped }
    }

    private fun join(thread: Thread) {
        if (thread === Thread.currentThread()) return
        try {
            thread.join(JOIN_TIMEOUT_MILLIS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        if (thread.isAlive) thread.interrupt()
    }

    companion object {
        private const val PUMP_BYTES = 16 * 1024
        private const val PUMP_READ_TIMEOUT_MILLIS = 250L
        private const val JOIN_TIMEOUT_MILLIS = 1_000L
        private val PREFERRED_PROTOCOLS = arrayOf("TLSv1.3", "TLSv1.2")

        @JvmStatic
        fun open(
            underlying: BlockingDuplexByteStream,
            pairRecord: LockdownPairRecord,
            handshakeTimeoutMillis: Long,
        ): BlockingDuplexByteStream {
            require(handshakeTimeoutMillis > 0) { "handshakeTimeoutMillis must be positive" }

            var server: ServerSocket? = null
            var rawClient: Socket? = null
            var bridge: Socket? = null
            var ssl: SSLSocket? = null
            val running = AtomicBoolean(true)
            val threads = ArrayList<Thread>(2)

            try {
                val loopback = InetAddress.getByName("127.0.0.1")
                val loopbackServer = ServerSocket(0, 1, loopback)
                server = loopbackServer
                val plainClient = Socket().apply { tcpNoDelay = true }
                rawClient = plainClient
                plainClient.connect(
                    InetSocketAddress(loopback, loopbackServer.localPort),
                    handshakeTimeoutMillis.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
                )
                val bridgeSocket = loopbackServer.accept().apply { tcpNoDelay = true }
                bridge = bridgeSocket
                loopbackServer.close()
                server = null

                val bridgeInput = bridgeSocket.getInputStream()
                val bridgeOutput = bridgeSocket.getOutputStream()

                fun stopBridge() {
                    running.set(false)
                    closeQuietly(bridgeSocket)
                    try { underlying.close() } catch (_: Exception) { }
                }

                threads += Thread(
                    {
                        val buffer = ByteArray(PUMP_BYTES)
                        try {
                            while (running.get()) {
                                val count = bridgeInput.read(buffer)
                                if (count < 0) break
                                if (count > 0) underlying.send(buffer.copyOf(count))
                            }
                        } catch (_: Exception) {
                        } finally {
                            stopBridge()
                        }
                    },
                    "lockdown-tls-out",
                ).apply {
                    isDaemon = true
                    start()
                }

                threads += Thread(
                    {
                        try {
                            while (running.get()) {
                                val chunk = underlying.recv(PUMP_BYTES, PUMP_READ_TIMEOUT_MILLIS) ?: continue
                                if (chunk.isEmpty()) break
                                bridgeOutput.write(chunk)
                                bridgeOutput.flush()
                            }
                        } catch (_: Exception) {
                        } finally {
                            stopBridge()
                        }
                    },
                    "lockdown-tls-in",
                ).apply {
                    isDaemon = true
                    start()
                }

                val context = LockdownTlsEngineFactory.createContext(pairRecord)
                ssl = context.socketFactory.createSocket(plainClient, "Device", 0, true) as SSLSocket
                rawClient = null
                ssl.useClientMode = true
                val supported = ssl.supportedProtocols.toSet()
                val enabled = PREFERRED_PROTOCOLS.filter(supported::contains).toTypedArray()
                if (enabled.isEmpty()) {
                    throw IphoneUsbException.Protocol(
                        "Platform SSLSocket supports neither TLSv1.2 nor TLSv1.3",
                    )
                }
                ssl.enabledProtocols = enabled
                ssl.soTimeout = handshakeTimeoutMillis
                    .coerceAtMost(Int.MAX_VALUE.toLong())
                    .coerceAtLeast(1L)
                    .toInt()
                ssl.startHandshake()
                ssl.soTimeout = 0

                return TlsSocketDuplexChannel(
                    underlying = underlying,
                    sslSocket = ssl,
                    bridgeSocket = bridgeSocket,
                    pumpThreads = threads,
                ).also {
                    ssl = null
                    bridge = null
                }
            } catch (error: Exception) {
                running.set(false)
                closeQuietly(ssl)
                closeQuietly(rawClient)
                closeQuietly(bridge)
                closeQuietly(server)
                try { underlying.close() } catch (_: Exception) { }
                threads.forEach { thread ->
                    if (thread !== Thread.currentThread()) {
                        try { thread.join(JOIN_TIMEOUT_MILLIS) } catch (_: InterruptedException) {
                            Thread.currentThread().interrupt()
                        }
                    }
                }
                throw error
            }
        }

        private fun closeQuietly(closeable: Closeable?) {
            try { closeable?.close() } catch (_: Exception) { }
        }
    }
}
