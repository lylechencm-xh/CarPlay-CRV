package com.shilapi.xcertplay

import java.util.BitSet

/** No generated toString: configuration secrets must never appear in diagnostics. */
internal class CrvHotspotConfiguration(
    private val ssid: String?,
    private val hidden: Boolean,
    private val secret: String?,
    private val requiresSecret: Boolean,
    private val security: List<BitSet>,
) {
    val canVerify: Boolean get() = !requiresSecret || visibleSecret(secret)

    fun matches(actual: CrvHotspotConfiguration): Boolean =
        canVerify && actual.canVerify && ssid == actual.ssid && hidden == actual.hidden &&
            requiresSecret == actual.requiresSecret && security == actual.security &&
            (!requiresSecret || unquote(secret) == unquote(actual.secret))

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
