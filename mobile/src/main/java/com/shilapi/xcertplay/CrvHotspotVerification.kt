package com.shilapi.xcertplay

import java.util.BitSet

/** Credentials borrowed from a vehicle AP; never include the password in diagnostics. */
internal class CrvExistingHotspotCredentials(val ssid: String, val passphrase: String) {
    companion object {
        fun from(ssid: String?, passphrase: String?, wpaPsk: Boolean): CrvExistingHotspotCredentials {
            fun normalize(value: String?): String = value.orEmpty().let {
                if (it.length >= 2 && it.first() == '"' && it.last() == '"') it.substring(1, it.length - 1) else it
            }
            val name = normalize(ssid)
            val secret = normalize(passphrase)
            require(wpaPsk) { "Existing vehicle hotspot must use WPA/WPA2 PSK" }
            require(name.toByteArray(Charsets.UTF_8).size in 1..32) { "Existing vehicle hotspot SSID is unavailable" }
            require(secret.length in 8..63 && secret.all { it.code in 32..126 } && !secret.all { it == '*' }) {
                "Existing vehicle hotspot password is unavailable or hidden"
            }
            return CrvExistingHotspotCredentials(name, secret)
        }
    }
}

/** No generated toString: configuration secrets must never appear in diagnostics. */
internal class CrvHotspotConfiguration(
    private val ssid: String?,
    private val hidden: Boolean,
    private val secret: String?,
    private val requiresSecret: Boolean,
    private val security: List<BitSet>,
) {
    val canVerify: Boolean get() = !requiresSecret || visibleSecret(secret)

    fun matches(actual: CrvHotspotConfiguration): Boolean = differences(actual).isEmpty()

    fun differences(actual: CrvHotspotConfiguration): List<String> {
        val changed = mutableListOf<String>()
        if (!canVerify || !actual.canVerify) changed += "unverifiable-secret"
        if (ssid != actual.ssid) changed += "ssid"
        if (hidden != actual.hidden) changed += "hidden"
        if (requiresSecret != actual.requiresSecret) changed += "authentication"
        if (security != actual.security) changed += "security"
        if (requiresSecret && unquote(secret) != unquote(actual.secret)) changed += "secret"
        return changed
    }

    private fun visibleSecret(value: String?): Boolean {
        val normalized = unquote(value)
        return !normalized.isNullOrEmpty() && !normalized.all { it == '*' }
    }

    private fun unquote(value: String?): String? =
        if (value != null && value.length >= 2 && value.first() == '"' && value.last() == '"') {
            value.substring(1, value.length - 1)
        } else value
}

/** Bounded monotonic polling, also used to test delayed vendor state/configuration updates. */
internal fun awaitCrvCondition(
    timeoutMs: Long,
    nowMs: () -> Long = { System.nanoTime() / 1_000_000L },
    sleep: (Long) -> Unit = { Thread.sleep(it) },
    condition: () -> Boolean,
): Boolean {
    val start = nowMs()
    while (true) {
        if (condition()) return true
        val remaining = timeoutMs - (nowMs() - start)
        if (remaining <= 0) return false
        sleep(minOf(100L, remaining))
    }
}
