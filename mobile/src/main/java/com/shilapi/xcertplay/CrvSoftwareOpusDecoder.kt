package com.shilapi.xcertplay

import org.concentus.OpusDecoder

/**
 * Stateful, per-stream pure Java Opus decoder for Android 4.2.2 / API17.
 * Only the decoded prefix of [pcm] is valid after each call; samples and
 * PCM storage are reused to avoid per-frame playback allocations.
 *
 * The stream's RTP framing and media routing remain owned by the CR-V sink.
 */
internal class CrvSoftwareOpusDecoder(sampleRate: Int, channels: Int) {
    private val decoder = OpusDecoder(sampleRate, channels)
    private val channelCount = channels
    private val maxFrameSamples = sampleRate * 120 / 1000
    private val samples = ShortArray(maxFrameSamples * channels)
    val pcm = ByteArray(samples.size * 2)

    /** Returns the valid little-endian PCM byte count after decoding one Opus packet. */
    fun decode(packet: ByteArray): Int {
        require(packet.isNotEmpty()) { "empty Opus packet" }
        val perChannel = decoder.decode(
            packet, 0, packet.size, samples, 0, maxFrameSamples, false,
        )
        val sampleCount = perChannel * channelCount
        for (index in 0 until sampleCount) {
            val value = samples[index].toInt()
            pcm[index * 2] = value.toByte()
            pcm[index * 2 + 1] = (value ushr 8).toByte()
        }
        return sampleCount * 2
    }
}
