package com.shilapi.xcertplay

import java.util.Locale

enum class CrvLogCategory(val directoryName: String) {
    CONNECTION("connection"), NETWORK("network"), AUDIO("audio"), VIDEO("video"),
    TOUCH("touch"), WIRELESS("wireless"), SYSTEM("system"), PROTOCOL("protocol"), ERRORS("errors");

    companion object {
        private val STAGE_PREFIX = Regex("^\\[[A-Z0-9_]+]\\s*")
        private val FAILURE_WORD = Regex("\\b(failed|failure)\\b")
        private val ENDED_EVENT = Regex("\\bended\\b(?!\\s*=false)")
        fun classify(message: String): CrvLogCategory {
            // Stage prefixes describe current UI state, not the message's subsystem.
            val text = message.replace(STAGE_PREFIX, "").lowercase(Locale.US)
            return when {
                text.startsWith("crvtrace\t") || text.startsWith("iap2 rx=") ||
                    text.startsWith("iap2 tx=") || text.startsWith("airplay rx ") ||
                    text.startsWith("airplay tx ") || text.startsWith("airplay setup") ||
                    text.startsWith("airplay teardown") || text.startsWith("trace airplay") -> PROTOCOL
                text.startsWith("snapshot ") || text.startsWith("system ") ||
                    text.startsWith("honda platform") || text.startsWith("honda mediacore") ||
                    text.startsWith("app ") -> SYSTEM
                "touch" in text || "hid report" in text || "hid reports" in text -> TOUCH
                "microphone" in text || text.startsWith("audio ") || text.startsWith("receive: audio") ||
                    "opus" in text -> AUDIO
                text.startsWith("video") || text.startsWith("receive: video") ||
                    "video decoder" in text || "screen stream" in text -> VIDEO
                "wi-fi" in text || "wifi" in text || "hotspot" in text ||
                    "bluetooth" in text || "bonjour" in text -> WIRELESS
                "ncm" in text || "usbmux" in text || "udp6" in text || "vpn" in text ||
                    text.startsWith("tcp ") || "transport error" in text || "tunnel" in text -> NETWORK
                else -> CONNECTION
            }
        }

        fun isFailure(message: String): Boolean {
            val text = message.lowercase(Locale.US)
            return message.startsWith("[ERROR]") || "\tFAULT\t" in message || "exception=" in text || " stack#" in text ||
                " cause=" in text || FAILURE_WORD.containsMatchIn(text) ||
                "timed out" in text || " error=" in text || "restoration incomplete" in text
        }

        fun includeInOverview(category: CrvLogCategory, message: String, failure: Boolean): Boolean {
            if (failure) return true
            if (category == CONNECTION) return true
            val text = message.replace(STAGE_PREFIX, "").lowercase(Locale.US)
            return " ready" in text || ENDED_EVENT.containsMatchIn(text) || " detached" in text ||
                " active" in text || " restored" in text || "first frame" in text ||
                text.startsWith("app started") || text.startsWith("app stopped")
        }
    }
}
