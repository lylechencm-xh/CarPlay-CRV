package com.shilapi.xcertplay

/** Tracks queued PCM on API17 where AudioTrack.underrunCount is unavailable. */
internal class CrvAudioBufferProgress(private val frameBytes: Int) {
    private var writtenBytes = 0L
    private var playedFrames = 0L
    private var lastHead = 0L

    init {
        require(frameBytes > 0)
    }

    fun written(bytes: Int) {
        if (bytes > 0) writtenBytes += bytes.toLong()
    }

    fun queuedBytes(rawHead: Int): Long {
        val head = rawHead.toLong() and 0xffff_ffffL
        playedFrames += (head - lastHead) and 0xffff_ffffL
        lastHead = head
        return (writtenBytes - playedFrames * frameBytes).coerceAtLeast(0L)
    }
}

/** Pure API17 starvation policy so the legacy AudioTrack recovery gate is unit-testable. */
internal object CrvMediaRebufferPolicy {
    fun shouldPause(
        isMedia: Boolean,
        playbackStarted: Boolean,
        compressedQueueEmpty: Boolean,
        lastPacketNs: Long,
        nowNs: Long,
        queuedBytes: Long,
        recoveryFloorBytes: Long,
        minimumPacketGapNs: Long,
    ): Boolean =
        isMedia &&
            playbackStarted &&
            compressedQueueEmpty &&
            lastPacketNs > 0L &&
            nowNs >= lastPacketNs &&
            nowNs - lastPacketNs >= minimumPacketGapNs &&
            queuedBytes <= recoveryFloorBytes
}
