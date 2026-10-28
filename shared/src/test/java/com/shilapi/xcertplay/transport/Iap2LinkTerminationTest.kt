package com.shilapi.xcertplay.transport

import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Test

class Iap2LinkTerminationTest {
    @Test fun eofKeepsItsOriginalCauseAndPreTerminationState() {
        val engine = Iap2LinkEngine()
        engine.start(wiredInitiator = true, nowMillis = 100)
        val state = engine.state()
        engine.feedEof()
        engine.feedEof()
        val terminal = engine.pollEvent() as Iap2LinkEngine.Event.Dead
        assertEquals("iAP2 transport EOF", terminal.reason)
        assertTrue(terminal.snapshot.contains("state=$state"))
        assertTrue(terminal.snapshot.contains("unacknowledged="))
        assertEquals(Iap2LinkEngine.State.DEAD, engine.state())
        assertNull(engine.pollEvent())
    }

    @Test fun eofFailureIsVisibleToCallerAndClosesTheOwnedStreamExactlyOnce() {
        val closes = AtomicInteger()
        val stream = object : BlockingDuplexByteStream {
            override fun send(data: ByteArray) {}
            override fun recv(maxBytes: Int, timeoutMillis: Long): ByteArray = ByteArray(0)
            override fun close() { closes.incrementAndGet() }
        }
        val channel = Iap2LinkChannel.open(stream)
        val failure = runCatching { channel.awaitReady(2000) }.exceptionOrNull()
        assertTrue(failure is IOException)
        assertTrue(failure!!.message!!.contains("iAP2 transport EOF"))
        assertTrue(failure.message!!.contains("lastWriteMs="))
        val closeFailure = runCatching { channel.close() }.exceptionOrNull()
        assertSame(failure, closeFailure)
        runCatching { channel.close() }
        assertEquals(1, closes.get())
    }

    @Test fun explicitCloseUnblocksThePumpWithoutInventingAnEofFailure() {
        val receiving = CountDownLatch(1)
        val closed = CountDownLatch(1)
        val closes = AtomicInteger()
        val stream = object : BlockingDuplexByteStream {
            override fun send(data: ByteArray) {}
            override fun recv(maxBytes: Int, timeoutMillis: Long): ByteArray {
                receiving.countDown()
                check(closed.await(2, TimeUnit.SECONDS))
                return ByteArray(0)
            }
            override fun close() { closes.incrementAndGet(); closed.countDown() }
        }
        val channel = Iap2LinkChannel.open(stream)
        assertTrue(receiving.await(2, TimeUnit.SECONDS))
        channel.close()
        channel.close()
        assertFalse(channel.awaitReady(0))
        assertEquals(1, closes.get())
    }
}
