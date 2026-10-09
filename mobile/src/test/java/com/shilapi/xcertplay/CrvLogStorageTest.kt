package com.shilapi.xcertplay

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class CrvLogStorageTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun longSessionKeepsOnlyTwoBoundedSegmentsAndNewestEntries() {
        val directory = temporary.newFolder("logs")
        val current = File(directory, logName(1))
        val storage = CrvLogStorage(directory, current, maxFileBytes = 128L, maxFiles = 4)

        repeat(12) { index ->
            storage.append("entry-$index " + "x".repeat(30))
        }

        val previous = File(directory, current.name.removeSuffix(".log") + "-previous.log")
        assertTrue(current.exists())
        assertTrue(previous.exists())
        assertTrue(current.length() <= 128L)
        assertTrue(previous.length() <= 128L)
        assertTrue(current.readText().contains("entry-11"))
        assertEquals(2, directory.listFiles()!!.size)
    }

    @Test fun startupPrunesOldAndOversizedSessionLogsWithoutTouchingOtherFiles() {
        val directory = temporary.newFolder("logs")
        val old = (1..8).map { index ->
            File(directory, logName(index)).apply {
                writeBytes(ByteArray(if (index == 8) 350 else 90) { 'x'.code.toByte() })
                setLastModified(1_000_000L + index * 1_000L)
            }
        }
        val unrelated = File(directory, "service.log").apply { writeText("keep me") }
        val similarName = File(directory, "carplay-crv-vmanual.log")
            .apply { writeText("also keep me") }
        val current = File(directory, logName(20))
        val storage = CrvLogStorage(directory, current, maxFileBytes = 100L, maxFiles = 4)

        storage.pruneOldLogs()
        storage.append("new session")
        storage.pruneOldLogs()

        assertTrue(unrelated.exists())
        assertTrue(similarName.exists())
        assertTrue(current.exists())
        assertFalse(old[7].exists())
        assertTrue(old[6].exists())
        val sessions = directory.listFiles()!!.filter { it.name.contains("-20261009-") }
        assertEquals(4, sessions.size)
        assertTrue(sessions.sumOf { it.length() } <= 400L)
    }

    private fun logName(index: Int): String =
        "carplay-crv-v0.2.28-crv-20261009-120000-" +
            index.toString().padStart(3, '0') + "-p1-b$index.log"
}
