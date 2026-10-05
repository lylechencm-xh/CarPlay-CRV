package com.shilapi.xcertplay

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Small API19-safe field log for in-car testing.
 *
 * Only stage/status messages are recorded. MFi private keys, certificates, Lockdown keys and
 * raw protocol payloads are never written here.
 */
class CrvDiagnostics(context: Context) {
    private val lock = Any()
    private val directory = context.getExternalFilesDir(null) ?: context.filesDir
    private val file = File(directory, "carplay-crv.log")
    private val formatter = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
    private var lastLine: String? = null
    private var lastLineAtMillis: Long = 0L

    fun path(): String = file.absolutePath

    fun log(stage: CrvConnectionStage, message: String) {
        log("[${stage.name}] $message")
    }

    fun log(message: String) {
        val safe = sanitize(message)
        synchronized(lock) {
            val now = android.os.SystemClock.elapsedRealtime()
            if (safe == lastLine && now - lastLineAtMillis < DEDUPE_WINDOW_MILLIS) return
            lastLine = safe
            lastLineAtMillis = now
            try {
                rotateIfNeeded()
                file.parentFile?.mkdirs()
                file.appendText("${formatter.format(Date())}  $safe\n")
            } catch (_: Exception) {
                // Diagnostics must never stop CarPlay bring-up.
            }
        }
    }

    private fun rotateIfNeeded() {
        if (!file.isFile || file.length() < MAX_BYTES) return
        val old = File(file.parentFile, "carplay-crv.previous.log")
        old.delete()
        file.renameTo(old)
    }

    private fun sanitize(message: String): String {
        var value = message
        val sensitiveWords = listOf(
            "privatekey",
            "identity.pk8",
            "certificate.p7b",
            "escrowbag",
            "challenge=",
            "signature=",
        )
        if (sensitiveWords.any { value.contains(it, ignoreCase = true) }) {
            value = "[sensitive diagnostic redacted]"
        }
        return value.replace('\n', ' ').replace('\r', ' ').take(MAX_LINE)
    }

    companion object {
        private const val MAX_BYTES = 256 * 1024L
        private const val MAX_LINE = 1024
        private const val DEDUPE_WINDOW_MILLIS = 1_000L
    }
}
