package com.shilapi.xcertplay.airplay

import org.junit.Assert.*
import org.junit.Test

class MediaDecryptProfilerTest {
    @Test fun samplesOnlyOneIn128CallsAndReportsSeparateCpuStages() {
        var cpu = 0L
        var now = 1L
        val reports = mutableListOf<String>()
        val profiler = MediaDecryptProfiler("test", { reports.add(it) }, { cpu += 1000; cpu }, { now },
            MediaCryptoDiagnostics.Sampling.FIXED)
        repeat(257) {
            if (it == 256) now += 30_000_000_000L
            if (profiler.begin()) {
                profiler.initialized(); profiler.allocated(); profiler.prefixed()
                profiler.processed(); profiler.authenticated(); profiler.finished(100)
            }
        }
        assertEquals(21_000L, cpu)
        assertEquals(2, reports.size)
        assertTrue(reports[0].contains("startup=true"))
        for (field in listOf("samples=2 sealedBytes=200", "initCpuUs=2", "allocateCpuUs=2",
                "prefixCpuUs=2", "processCpuUs=2", "finalCpuUs=2", "trimCpuUs=2", "totalCpuUs=12")) {
            assertTrue(field, reports[1].contains(field))
        }
    }

    @Test fun offNeverReadsClocksAndJitterSelectsOneDifferentSlotPerBlockReproducibly() {
        val off = MediaDecryptProfiler("test", { fail("off must not report") },
            { error("off must not read CPU clock") }, { error("off must not read wall clock") },
            MediaCryptoDiagnostics.Sampling.OFF)
        repeat(1000) { assertFalse(off.begin()) }
        fun slots(): List<Int> {
            val profiler = MediaDecryptProfiler("test", {}, { 0L }, { 1L },
                MediaCryptoDiagnostics.Sampling.JITTERED, seed = 123)
            return (0 until 128 * 32).filter {
                profiler.begin().also { selected -> if (selected) profiler.failed() }
            }
        }
        val selected = slots()
        assertEquals(selected, slots())
        assertEquals(32, selected.size)
        assertEquals((0 until 32).toList(), selected.map { it / 128 })
        assertTrue(selected.map { it % 128 }.toSet().size > 16)
    }

    @Test fun authenticationFailureDoesNotContaminateSuccessfulStageTotals() {
        var cpu = 0L
        val reports = mutableListOf<String>()
        val profiler = MediaDecryptProfiler("test", { reports.add(it) }, { cpu += 1000; cpu }, { 1L },
            MediaCryptoDiagnostics.Sampling.FIXED)
        assertTrue(profiler.begin())
        profiler.initialized(); profiler.allocated(); profiler.prefixed(); profiler.processed()
        profiler.failed()
        repeat(127) { assertFalse(profiler.begin()) }
        assertTrue(profiler.begin())
        profiler.initialized(); profiler.allocated(); profiler.prefixed(); profiler.processed()
        profiler.authenticated(); profiler.finished(99)
        assertEquals(1, reports.size)
        assertTrue(reports.single().contains("samples=1 sealedBytes=99 failedSamples=1"))
        assertTrue(reports.single().contains("totalCpuUs=6"))
    }

    @Test fun costAlertRequiresSustainedIncreaseAndIgnoresRateChanges() {
        val trend = MediaDecryptCostTrend()
        repeat(3) { assertNull(trend.observe(1000, 1000, 100)) }
        assertNull(trend.observe(2000, 2000, 200))
        assertNull(trend.observe(2500, 1000, 100))
        assertNull(trend.observe(1000, 1000, 100)) // transient resets
        repeat(2) { assertNull(trend.observe(2500, 1000, 100)) }
        assertNotNull(trend.observe(2500, 1000, 100))
        assertNull(trend.observe(2500, 1000, 100)) // bounded alert volume
    }
}
