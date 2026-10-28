package com.shilapi.xcertplay

import org.junit.Assert.*
import org.junit.Test

class CrvDiagnosticQueueTest {
    private fun entry(message: String, important: Boolean = false, critical: Boolean = false) =
        CrvDiagnosticQueue.Entry(message, 0, 0, important, critical)

    @Test fun terminalEvidenceSurvivesAConnectionMessageFlood() {
        val queue = CrvDiagnosticQueue(3)
        queue.offer(entry("summary", true, true))
        queue.offer(entry("routine"))
        queue.offer(entry("connection", true))
        queue.offer(entry("terminal", true, true))
        repeat(10) { queue.offer(entry("connection-$it", true)) }
        queue.close()
        val remaining = generateSequence { queue.take()?.message }.toList()
        assertEquals(listOf("summary", "terminal", "connection-9"), remaining)
        assertEquals(11L, queue.dropped())
        assertTrue(queue.dropSummary().contains("droppedCritical=0"))
    }

    @Test fun criticalOnlyOverloadStaysBoundedAndAccountsLoss() {
        val queue = CrvDiagnosticQueue(1)
        queue.offer(entry("a", true, true))
        queue.offer(entry("b", true, true))
        queue.offer(entry("ordinary", true))
        queue.close()
        assertEquals("b", queue.take()!!.message)
        assertNull(queue.take())
        assertTrue(queue.dropSummary().contains("droppedImportant=1 droppedCritical=1"))
    }

    @Test fun runtimeCheckpointsAndPerformanceAlertsReachOverview() {
        for (text in listOf("Runtime media tid=42 checkpoint=true", "Runtime media crypto startup=true",
                "Runtime media cost-change worker=audio", "Runtime media cost-window worker=audio phase=before",
                "Audio receive ended type=100")) {
            assertTrue(CrvLogCategory.includeInOverview(CrvLogCategory.classify(text), text, false))
        }
        assertFalse(CrvLogCategory.includeInOverview(CrvLogCategory.SYSTEM,
            "Runtime media tid=42 checkpoint=false", false))
    }
}
