package com.shilapi.xcertplay

import kotlin.math.abs
import kotlin.math.min

/** PCM16LE navigation-only gain. Never modifies shared decoder/packet buffers. */
internal class CrvNavigationPcmGain {
    var inputPeak = 0; private set
    var appliedGain = 1f; private set
    fun apply(source: ByteArray, offset: Int, count: Int, output: ByteArray, gain: Float) {
        require(offset >= 0 && count >= 0 && count % 2 == 0 && offset <= source.size - count)
        require(output.size >= count)
        val requested = if (gain.isNaN() || gain.isInfinite()) 1f else gain.coerceIn(1f, 3f)
        var peak = 0
        var index = 0
        while (index < count) {
            peak = maxOf(peak, abs(sample(source, offset + index)))
            index += 2
        }
        // One gain for the entire block preserves stereo balance and prevents hard clipping.
        inputPeak = peak
        val applied = if (requested == 1f || peak == 0) requested else min(requested, 32767f / peak)
        appliedGain = applied
        index = 0
        while (index < count) {
            val value = (sample(source, offset + index) * applied).toInt().coerceIn(-32768, 32767)
            output[index] = value.toByte()
            output[index + 1] = (value shr 8).toByte()
            index += 2
        }
    }

    private fun sample(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 255) or (bytes[offset + 1].toInt() shl 8)).toShort().toInt()

    companion object {
        // Calls, Siri and alerts intentionally retain their original volume.
        fun isNavigation(type: Int, audioType: String): Boolean =
            audioType == "navigation" || audioType == "guidance" ||
                (type == 101 && audioType == "default")
    }
}
