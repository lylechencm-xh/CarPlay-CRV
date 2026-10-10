package com.shilapi.xcertplay

import org.junit.Assert.*
import org.junit.Test

class CrvDecodedVideoOutputTest {
    @Test fun backlogReleasesOldDecodedFramesAndRendersOnlyNewest() {
        val releases = mutableListOf<Pair<Int, Boolean>>()
        val output = CrvDecodedVideoOutput { index, render -> releases += index to render }
        output.offer(2); output.offer(4); output.offer(7)
        output.finish(true)
        assertEquals(listOf(2 to false, 4 to false, 7 to true), releases)
        assertEquals(2L, output.discardedTotal)
        output.finish(true)
        assertEquals(3, releases.size)
    }

    @Test fun keyframeRecoveryAndShutdownDoNotRenderPendingOutput() {
        val releases = mutableListOf<Pair<Int, Boolean>>()
        val output = CrvDecodedVideoOutput { index, render -> releases += index to render }
        output.offer(3); output.finish(false)
        output.offer(4); output.reset(); output.finish(true)
        output.offer(5); output.finish(true)
        assertEquals(listOf(3 to false, 5 to true), releases)
    }
}
