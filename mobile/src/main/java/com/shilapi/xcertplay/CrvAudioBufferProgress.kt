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
    /** Keep playing through brief receive jitter while usable PCM remains in AudioTrack. */
    fun recoveryFloorBytes(primeTargetBytes: Int, sampleRate: Int, frameBytes: Int): Long =
        minOf(primeTargetBytes / 2L, sampleRate.toLong() * frameBytes * 30L / 1000L)

    fun shouldPause(
        isMedia: Boolean,
        playbackStarted: Boolean,
        compressedQueueEmpty: Boolean,
        lastPacketNs: Long,
        nowNs: Long,
        queuedBytes: Long,
        recoveryFloorBytes: Long,
        minimumPacketGapNs: Long,
        drainingTail: Boolean = false,
    ): Boolean =
        isMedia &&
            !drainingTail &&
            playbackStarted &&
            compressedQueueEmpty &&
            lastPacketNs > 0L &&
            nowNs >= lastPacketNs &&
            nowNs - lastPacketNs >= minimumPacketGapNs &&
            queuedBytes <= recoveryFloorBytes
}

/** Drain a short recovered tail only after fresh PCM arrives and the stream becomes idle. */
internal object CrvMediaTailResumePolicy {
    fun shouldResume(
        rebuffering: Boolean, freshPcm: Boolean, playbackStarted: Boolean,
        primedBytes: Int, queueEmpty: Boolean, lastWriteNs: Long,
        lastPacketNs: Long, nowNs: Long,
    ): Boolean {
        val idleNs = if (rebuffering) 600_000_000L else 180_000_000L
        return !playbackStarted && primedBytes > 0 && queueEmpty &&
            (!rebuffering || freshPcm) && lastWriteNs > 0L &&
            nowNs >= lastWriteNs && nowNs - lastWriteNs >= idleNs &&
            (lastPacketNs == 0L || (nowNs >= lastPacketNs && nowNs - lastPacketNs >= idleNs))
    }
}

/** A paused MODE_STREAM track cannot consume a write larger than its free buffer. */
internal object CrvAudioPrimeWriteSize {
    fun next(remainingBytes: Int, playbackStarted: Boolean, primeTargetBytes: Int, primedBytes: Int): Int =
        if (playbackStarted) remainingBytes
        else minOf(remainingBytes, (primeTargetBytes - primedBytes).coerceAtLeast(1))
}
