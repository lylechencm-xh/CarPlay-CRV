package com.shilapi.xcertplay.airplay

import org.junit.Assert.*
import org.junit.Test

class MediaCostHistoryTest {
    @Test fun archivesOnlyBoundedWindowsAroundSustainedCostChange() {
        val reports = mutableListOf<String>()
        val history = MediaCostHistory("audio:100:11", { reports.add(it) })
        repeat(30) { history.record("window=$it", 1000, 1000, 100) }
        assertTrue(reports.isEmpty())
        repeat(3) { history.record("slow=$it", 2500, 1000, 100) }
        assertEquals(26, reports.size) // alert, 24 before, trigger
        assertTrue(reports[0].contains("worker=audio:100:11"))
        assertTrue(reports[1].endsWith("window=8"))
        assertTrue(reports.last().endsWith("phase=trigger slow=2"))
        repeat(100) { history.record("after=$it", 2500, 1000, 100) }
        assertEquals(50, reports.size)
        assertTrue(reports.last().endsWith("phase=after after=23"))
    }

    @Test fun endingAndEmptyWindowsCannotTriggerAChange() {
        val reports = mutableListOf<String>()
        val history = MediaCostHistory("video:1", { reports.add(it) }, minimumPackets = 1)
        repeat(3) { history.record("baseline", 1000, 1000, 1) }
        repeat(3) { history.record("end", 9000, 1000, 1, ended = true) }
        repeat(3) { history.record("idle", 9000, 0, 0) }
        assertTrue(reports.isEmpty())
    }
}
