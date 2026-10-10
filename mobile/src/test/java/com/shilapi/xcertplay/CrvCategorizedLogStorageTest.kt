package com.shilapi.xcertplay

import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class CrvCategorizedLogStorageTest {
    @get:Rule val temporary = TemporaryFolder()
    private val name = "carplay-crv-v0.2.28.1-20261010-120000-001-p1-b1.log"

    @Test fun routesDetailsAndDuplicatesFailuresWithIdenticalSequence() {
        val dir = temporary.newFolder()
        val main = File(dir, name)
        val storage = CrvCategorizedLogStorage(dir, main)
        storage.append("#1 audio detail", CrvLogCategory.AUDIO, false, false)
        storage.append("#2 video failed", CrvLogCategory.VIDEO, true, true)
        storage.close()
        assertEquals("#1 audio detail\n", File(dir, "logs/audio/$name").readText())
        assertEquals("#2 video failed\n", File(dir, "logs/video/$name").readText())
        assertEquals("#2 video failed\n", File(dir, "logs/errors/$name").readText())
        assertEquals("#2 video failed\n", main.readText())
        storage.append("late", CrvLogCategory.AUDIO, false, true)
        assertFalse(main.readText().contains("late"))
    }

    @Test fun busyVideoRotatesIndependentlyAndPruningPreservesUnrelatedFiles() {
        val dir = temporary.newFolder()
        val storage = CrvCategorizedLogStorage(dir, File(dir, name), 80L, 4)
        storage.append("important error", CrvLogCategory.ERRORS, true, true)
        repeat(20) { storage.append("video-$it " + "x".repeat(25), CrvLogCategory.VIDEO, false, false) }
        val video = File(dir, "logs/video")
        val unrelated = File(video, "notes.txt").apply { writeText("keep") }
        repeat(8) { index ->
            File(video, name.replace("-b1.log", "-b${index + 2}.log")).writeText("old")
        }
        storage.pruneOldLogs()
        storage.close()
        assertEquals("important error\n", File(dir, "logs/errors/$name").readText())
        assertTrue(unrelated.exists())
        val logs = video.listFiles()!!.filter { it.extension == "log" }
        assertTrue(logs.size <= 4)
        assertTrue(logs.sumOf { it.length() } <= 320L)
        assertTrue(File(video, name).readText().contains("video-19"))
    }

    @Test fun failedCategoryDestinationDoesNotPreventErrorOrOverviewWrites() {
        val dir = temporary.newFolder()
        File(dir, "logs/audio").apply { parentFile.mkdirs(); writeText("blocks category folder") }
        val main = File(dir, name)
        val storage = CrvCategorizedLogStorage(dir, main)
        storage.append("#4 audio failed", CrvLogCategory.AUDIO, true, true)
        storage.close()
        assertTrue(main.readText().contains("#4"))
        assertTrue(File(dir, "logs/errors/$name").readText().contains("#4"))
    }

    @Test fun stageDoesNotOverrideSubsystemAndZeroFailureCountersAreNotErrors() {
        assertEquals(CrvLogCategory.AUDIO, CrvLogCategory.classify("[IAP2] Receive: audio type=100"))
        assertEquals(CrvLogCategory.NETWORK, CrvLogCategory.classify("[ERROR] UDP6 receive socketDrops=2"))
        assertEquals(CrvLogCategory.WIRELESS, CrvLogCategory.classify("[NCM] Wi-Fi hotspot ready"))
        assertEquals(CrvLogCategory.PROTOCOL, CrvLogCategory.classify("[IAP2] iap2 rx=0x5001"))
        assertEquals(CrvLogCategory.TOUCH, CrvLogCategory.classify("Touch delivery failures=0"))
        assertFalse(CrvLogCategory.isFailure("Touch delivery failures=0"))
        assertTrue(CrvLogCategory.isFailure("[ERROR] UDP6 receive"))
        assertFalse(CrvLogCategory.includeInOverview(CrvLogCategory.AUDIO, "Receive: audio ended=false", false))
        assertTrue(CrvLogCategory.includeInOverview(CrvLogCategory.AUDIO, "Audio receive ended type=100", false))
    }
}
