package com.shilapi.xcertplay.airplay

import org.junit.Assert.*
import org.junit.Test

class MediaWorkerSnapshotTest {
    @Test fun parsesCoreAndNiceDespiteSpacesAndParenthesesInThreadName() {
        val fields = MutableList(37) { "0" }
        fields[0] = "R"; fields[16] = "-4"; fields[36] = "2"
        val files = mapOf(
            "/proc/self/task/42/stat" to "42 (screen (decrypt)) ${fields.joinToString(" ")}",
            "/proc/self/task/42/schedstat" to "1000 2000 3",
            "/sys/devices/system/cpu/cpu2/cpufreq/scaling_cur_freq" to "800000",
            "/sys/class/thermal/thermal_zone0/temp" to "65000",
        )
        val result = MediaWorkerSnapshot.collect(42, true) { files[it] }
        assertTrue(result.contains("cpu=2 freqKHz=800000 nice=-4"))
        assertTrue(result.contains("schedRunNs=1000 schedWaitNs=2000 schedSlices=3"))
        assertTrue(result.contains("thermalRaw=[0:65000,1:unknown"))
    }

    @Test fun missingOrMalformedOemFilesRemainUnknown() {
        val result = MediaWorkerSnapshot.collect(42, false) { "unavailable" }
        assertTrue(result.contains("visible=false cpu=unknown freqKHz=unknown nice=unknown"))
        assertTrue(result.contains("schedRunNs=unknown schedWaitNs=unknown"))
    }
}
