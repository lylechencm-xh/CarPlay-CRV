package com.shilapi.xcertplay

import android.content.Context
import java.io.Closeable
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * API17-safe categorized field log with a shared event timeline.
 *
 * Sensitive diagnostics are redacted before any destination is written.
 */
class CrvDiagnostics(context: Context) : Closeable {
    private val lock = Any()
    private val directory = context.getExternalFilesDir(null) ?: context.filesDir
    private val formatter = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
    private val storage = CrvCategorizedLogStorage(directory, File(directory, uniqueSessionFileName(context)))
    private var lastLine: String? = null
    private var lastLineAtMillis: Long = 0L
    private val startedAtMillis = android.os.SystemClock.elapsedRealtime()
    private var sequence = 0L

    fun path(): String = storage.file.absolutePath

    fun pruneOldLogs() = storage.pruneOldLogs()

    fun log(stage: CrvConnectionStage, message: String) {
        log("[${stage.name}] $message")
    }

    fun log(message: String) {
        val safe = sanitize(message)
        val category = CrvLogCategory.classify(message)
        val failure = CrvLogCategory.isFailure(message)
        synchronized(lock) {
            val now = android.os.SystemClock.elapsedRealtime()
            if (safe == lastLine && now - lastLineAtMillis < DEDUPE_WINDOW_MILLIS) return
            lastLine = safe
            lastLineAtMillis = now
            sequence += 1
            try {
                val elapsed = now - startedAtMillis
                storage.append(
                    formatter.format(Date()) +
                        "  #" + sequence +
                        " +" + elapsed + "ms  " +
                        safe,
                    category = category,
                    failure = failure,
                    overviewEvent = CrvLogCategory.includeInOverview(category, message, failure),
                )
            } catch (_: Exception) {
                // Diagnostics must never stop CarPlay bring-up.
            }
        }
    }

    override fun close() {
        storage.close()
    }

    fun logFailure(label: String, error: Throwable) {
        log(
            label + " exception=" + error.javaClass.name +
                " message=" + (error.message ?: "none"),
        )
        error.stackTrace.take(MAX_STACK_FRAMES).forEachIndexed { index, frame ->
            log(
                label + " stack#" + index + "=" +
                    frame.className + "." + frame.methodName +
                    "(" + (frame.fileName ?: "?") + ":" + frame.lineNumber + ")",
            )
        }
        error.cause?.takeIf { it !== error }?.let { cause ->
            log(
                label + " cause=" + cause.javaClass.name +
                    " message=" + (cause.message ?: "none"),
            )
        }
    }

    private fun sanitize(message: String): String {
        var value = message
        val sensitiveWords = listOf(
            "privatekey=",
            "private-key=",
            "escrowbag=",
            "challenge=",
            "signature=",
            "password=",
            "passphrase=",
            "presharedkey=",
            "token=",
        )
        if (sensitiveWords.any { value.contains(it, ignoreCase = true) }) {
            value = "[sensitive diagnostic redacted]"
        }
        return value.replace('\n', ' ').replace('\r', ' ').take(MAX_LINE)
    }

    private fun uniqueSessionFileName(context: Context): String {
        val version = runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull()?.replace(Regex("[^A-Za-z0-9._-]"), "_") ?: "unknown"
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.US).format(Date())
        val pid = android.os.Process.myPid()
        val bootMs = android.os.SystemClock.elapsedRealtime()
        return "carplay-crv-v" + version + "-" + stamp + "-p" + pid + "-b" + bootMs + ".log"
    }

    companion object {
        private const val MAX_LINE = 4096
        private const val MAX_STACK_FRAMES = 12
        private const val DEDUPE_WINDOW_MILLIS = 1_000L
    }
}
