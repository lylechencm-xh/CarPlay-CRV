package com.shilapi.xcertplay

/** One attenuation decision for music; command and local speech detection never multiply. */
internal class CrvAudioDucking(private val nowMs: () -> Long = { System.nanoTime() / 1_000_000L }) {
    private var commandDuck = false
    private var voiceUntilMs = Long.MIN_VALUE

    @Synchronized fun command(duck: Boolean) { commandDuck = duck }

    @Synchronized fun voicePcm(bytes: ByteArray, offset: Int, count: Int) {
        if (offset < 0 || count < 2 || offset > bytes.size - count) return
        var index = offset
        while (index + 1 < offset + count) {
            val sample = ((bytes[index].toInt() and 0xff) or (bytes[index + 1].toInt() shl 8)).toShort().toInt()
            if (sample > 64 || sample < -64) {
                voiceUntilMs = nowMs() + VOICE_HOLD_MS
                return
            }
            index += 2
        }
    }

    @Synchronized fun mediaGain(): Float = if (commandDuck || nowMs() < voiceUntilMs) DUCK_GAIN else 1f
    @Synchronized fun reset() { commandDuck = false; voiceUntilMs = Long.MIN_VALUE }

    companion object {
        const val DUCK_GAIN = 0.2f
        const val VOICE_HOLD_MS = 700L
        fun isPrompt(type: Int, audioType: String): Boolean = audioType in
            setOf("guidance", "navigation", "alert", "speechrecognition", "telephony") ||
            (type == 101 && audioType == "default")
        fun isMusic(type: Int, audioType: String): Boolean = audioType == "media" ||
            (type == 100 && audioType == "default")
    }
}
