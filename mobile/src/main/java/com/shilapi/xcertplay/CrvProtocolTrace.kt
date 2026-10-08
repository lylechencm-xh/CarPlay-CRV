package com.shilapi.xcertplay

import java.net.URLEncoder

/** Layers shared with the JVM virtual CR-V importer. Keep names wire-compatible. */
internal enum class CrvProtocolLayer {
    USB,
    NCM,
    USBMUX,
    LOCKDOWN,
    MFI,
    CARPLAY_SESSION,
}

internal enum class CrvProtocolDirection {
    HOST_TO_DEVICE,
    DEVICE_TO_HOST,
    SIGNAL,
    FAULT,
}

/**
 * API17-safe structured recorder for production connection milestones.
 *
 * Raw payloads are deliberately never accepted. A record carries only its byte count and a
 * bounded diagnostic detail, so MFi keys, pairing records and user data cannot accidentally be
 * written to the field log.
 */
internal class CrvProtocolTraceRecorder(
    private val emit: (String) -> Unit,
    private val clockMillis: () -> Long = { System.nanoTime() / 1_000_000L },
) {
    private val startedAtMillis = clockMillis()
    private var sequence = 0L

    @Synchronized
    fun signal(
        layer: CrvProtocolLayer,
        channel: String,
        byteCount: Int = 0,
        detail: String = "",
    ) {
        record(layer, CrvProtocolDirection.SIGNAL, channel, byteCount, detail)
    }

    @Synchronized
    fun fault(
        layer: CrvProtocolLayer,
        channel: String,
        detail: String,
    ) {
        record(layer, CrvProtocolDirection.FAULT, channel, 0, detail)
    }

    @Synchronized
    fun record(
        layer: CrvProtocolLayer,
        direction: CrvProtocolDirection,
        channel: String,
        byteCount: Int = 0,
        detail: String = "",
    ) {
        require(byteCount >= 0) { "byteCount must be non-negative" }
        val offset = (clockMillis() - startedAtMillis).coerceAtLeast(0L)
        val line = listOf(
            PREFIX,
            VERSION,
            sequence++.toString(),
            offset.toString(),
            layer.name,
            direction.name,
            encode(clean(channel)),
            byteCount.toString(),
            encode(clean(detail)),
        ).joinToString("\t")
        runCatching { emit(line) }
    }

    private fun clean(value: String): String {
        val oneLine = value.replace('\n', ' ').replace('\r', ' ').take(MAX_FIELD)
        return if (
            SENSITIVE.any { oneLine.contains(it, ignoreCase = true) } ||
            IDENTIFIERS.any { it.containsMatchIn(oneLine) }
        ) {
            REDACTED
        } else {
            oneLine
        }
    }

    private fun encode(value: String): String =
        URLEncoder.encode(value, "UTF-8").replace("+", "%20")

    companion object {
        const val PREFIX = "CRVTRACE"
        const val VERSION = "1"
        private const val MAX_FIELD = 512
        private const val REDACTED = "[redacted]"
        private val IDENTIFIERS = listOf(
            Regex("(?i)(?:[0-9a-f]{2}:){5}[0-9a-f]{2}"),
            Regex("(?i)[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"),
            Regex("\\b(?:\\d{1,3}\\.){3}\\d{1,3}\\b"),
            Regex("(?i)(?:[0-9a-f]{0,4}:){2,}[0-9a-f:%]+"),
        )
        private val SENSITIVE = listOf(
            "privatekey", "private-key", "escrowbag", "challenge",
            "signature", "password", "token", "certificate",
        )
    }
}
