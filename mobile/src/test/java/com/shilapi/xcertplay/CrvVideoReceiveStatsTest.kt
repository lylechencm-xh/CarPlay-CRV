package com.shilapi.xcertplay

import org.junit.Assert.*
import org.junit.Test

class CrvVideoReceiveStatsTest {
    @Test fun videoTotalsResetTogetherAndNeverUseStageMaximaAsTotals() {
        var now = 1L
        val output = mutableListOf<String>()
        val type = Class.forName("com.shilapi.xcertplay.airplay.StreamReceiveStats")
        val stats = type.constructors.first { it.parameterCount == 4 }.newInstance(
            "video", { line: String -> output.add(line); Unit }, "video:test", { now })
        val stage = type.getMethod("videoStages", Long::class.javaPrimitiveType,
            Long::class.javaPrimitiveType, Long::class.javaPrimitiveType,
            Int::class.javaPrimitiveType, Long::class.javaPrimitiveType)
        stage.invoke(stats, 20_000L, 0L, 0L, 100, 10_000L)
        stage.invoke(stats, 30_000L, 0L, 0L, 200, 15_000L)
        now += 5_000_000_000L
        val flush = type.getMethod("flush", Boolean::class.javaPrimitiveType)
        flush.invoke(stats, true)
        assertTrue(output.last().contains("videoFrames=2 decryptTotalUs=50 decryptCpuTotalUs=25 decryptCpuMaxUs=15"))
        now += 5_000_000_000L
        flush.invoke(stats, true)
        assertTrue(output.last().contains("videoFrames=0 decryptTotalUs=0 decryptCpuTotalUs=0 decryptCpuMaxUs=0"))
    }
}
