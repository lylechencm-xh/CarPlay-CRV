package com.shilapi.xcertplay.airplay

import java.io.ByteArrayInputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.net.InetAddress
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class AirPlayDataStreamReaderTest {
    private val key = ByteArray(32) { it.toByte() }
    private fun reader(): AirPlayDataStreamReader {
        val opener = AirPlayChaChaOpener(key)
        return AirPlayDataStreamReader { counter, aad, sealed, length ->
            opener.open(AirPlayCrypto.nonce64(counter), sealed, length = length, aad = aad)
        }
    }
    private fun frame(plain: ByteArray, counter: Long): ByteArray {
        val aad = byteArrayOf(plain.size.toByte(), (plain.size shr 8).toByte())
        return aad + AirPlayCrypto.chachaSeal(key, AirPlayCrypto.nonce64(counter), plain, aad)
    }
    private fun pkg(size: Int): ByteArray = ByteArray(size) { (it % 251).toByte() }.apply {
        this[0] = (size ushr 24).toByte(); this[1] = (size ushr 16).toByte()
        this[2] = (size ushr 8).toByte(); this[3] = size.toByte()
    }

    @Test fun fragmentedEncryptedFramesPreservePackagesAndNonceOrder() {
        val original = pkg(100_000)
        val parts = original.toList().chunked(4096).map { it.toByteArray() }
        val wire = parts.mapIndexed { index, bytes -> frame(bytes, index.toLong()) }
            .fold(ByteArray(0)) { all, bytes -> all + bytes }
        val input = object : ByteArrayInputStream(wire) {
            override fun read(target: ByteArray, offset: Int, length: Int): Int =
                super.read(target, offset, minOf(length, 7))
        }
        val packages = AirPlayPackageBuffer(200_000)
        val frames = reader()
        val received = mutableListOf<ByteArray>()
        while (true) {
            packages.append(frames.read(input) ?: break) { bytes, size -> received += bytes.copyOf(size) }
        }
        packages.finish()
        assertEquals(1, received.size)
        assertArrayEquals(original, received.single())
    }

    @Test fun coalescedPackagesReuseStorageWithoutCorruptingDeliveredCopies() {
        val packages = AirPlayPackageBuffer(1024)
        val original = listOf(pkg(100), pkg(32), pkg(500), pkg(40))
        val received = mutableListOf<ByteArray>()
        val storage = mutableListOf<ByteArray>()
        packages.append(original.fold(ByteArray(0)) { all, bytes -> all + bytes }) { bytes, size ->
            storage += bytes; received += bytes.copyOf(size)
        }
        packages.finish()
        original.forEachIndexed { index, bytes -> assertArrayEquals(bytes, received[index]) }
        assertSame(storage[0], storage[1])
        assertSame(storage[2], storage[3])
    }

    @Test fun invalidPackageLengthsFailBeforeFurtherAccumulation() {
        for (size in listOf(0, 31, 1025, -1)) {
            val header = ByteArray(32)
            header[0] = (size ushr 24).toByte(); header[1] = (size ushr 16).toByte()
            header[2] = (size ushr 8).toByte(); header[3] = size.toByte()
            try {
                AirPlayPackageBuffer(1024).append(header) { _, _ -> fail("invalid delivered") }
                fail("invalid length accepted")
            } catch (_: IOException) { }
        }
    }

    @Test fun eofBetweenFramesIsCleanButTruncatedFrameOrPackageFails() {
        assertNull(reader().read(ByteArrayInputStream(ByteArray(0))))
        for (wire in listOf(byteArrayOf(2), frame(byteArrayOf(1, 2), 0).dropLast(1).toByteArray())) {
            try { reader().read(ByteArrayInputStream(wire)); fail("truncated frame accepted") }
            catch (_: EOFException) { }
        }
        val packages = AirPlayPackageBuffer(1024)
        packages.append(pkg(100).copyOf(50)) { _, _ -> fail("incomplete delivered") }
        try { packages.finish(); fail("truncated package accepted") } catch (_: EOFException) { }
    }

    @Test fun failedAuthenticationDeliversNothingAndKeepsNonce() {
        val frames = reader()
        val plain = pkg(32)
        val corrupt = frame(plain, 0).apply { this[lastIndex] = (this[lastIndex].toInt() xor 1).toByte() }
        try { frames.read(ByteArrayInputStream(corrupt)); fail("invalid tag accepted") }
        catch (_: org.bouncycastle.crypto.InvalidCipherTextException) { }
        assertArrayEquals(plain, frames.read(ByteArrayInputStream(frame(plain, 0))))
    }

    @Test fun zeroByteBulkReadStillMakesProgress() {
        val delegate = ByteArrayInputStream(frame(pkg(32), 0))
        val input = object : InputStream() {
            override fun read(): Int = delegate.read()
            override fun read(target: ByteArray, offset: Int, length: Int): Int = 0
        }
        assertArrayEquals(pkg(32), reader().read(input))
    }

    @Test fun iapTunnelDeliversFragmentedBodyInOrder() {
        val received = mutableListOf<ByteArray>()
        val delivered = CountDownLatch(2)
        val tunnel = IapTunnel(key, InetAddress.getByName("127.0.0.1"))
        try {
            val port = tunnel.listen(object : IapTunnel.Listener {
                override fun onIap(bytes: ByteArray) { received += bytes; delivered.countDown() }
            })
            Socket("127.0.0.1", port).use { socket ->
                val original = listOf(pkg(9000), pkg(100)).onEach {
                    "comm".asciiBytes().copyInto(it, 16)
                }
                val all = original.fold(ByteArray(0)) { result, bytes -> result + bytes }
                var counter = 0L
                all.toList().chunked(500).forEach { chunk ->
                    socket.getOutputStream().write(frame(chunk.toByteArray(), counter++))
                }
                socket.getOutputStream().flush()
                assertTrue(delivered.await(3, TimeUnit.SECONDS))
                original.forEachIndexed { index, bytes -> assertArrayEquals(bytes.copyOfRange(32, bytes.size), received[index]) }
            }
        } finally { tunnel.close() }
    }

    @Test fun settingsChannelRepliesToEachCoalescedSyncWithMatchingSequence() {
        val channel = VideoSettingsChannel(key, key) { }
        try {
            val port = channel.listen(InetAddress.getByName("127.0.0.1"))
            Socket("127.0.0.1", port).use { socket ->
                socket.soTimeout = 3000
                val requests = listOf(3, 4).map { number ->
                    ByteArray(32).apply {
                        this[3] = 32
                        "sync".asciiBytes().copyInto(this, 4)
                        this[20] = number.toByte()
                    }
                }
                socket.getOutputStream().write(frame(requests[0] + requests[1], 0))
                socket.getOutputStream().flush()
                val replies = reader()
                requests.forEach { request ->
                    val reply = replies.read(socket.getInputStream())!!
                    assertEquals("rply", String(reply, 4, 4, Charsets.US_ASCII))
                    assertArrayEquals(request.copyOfRange(20, 28), reply.copyOfRange(20, 28))
                }
            }
        } finally { channel.close() }
    }
}
