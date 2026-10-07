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
    private val startedAtMillis = android.os.SystemClock.elapsedRealtime()
    private var sequence = 0L

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
            sequence += 1
            try {
                rotateIfNeeded()
                file.parentFile?.mkdirs()
                val elapsed = now - startedAtMillis
                file.appendText(
                    formatter.format(Date()) +
                        "  #" + sequence +
                        " +" + elapsed + "ms  " +
                        safe + "\n",
                )
            } catch (_: Exception) {
                // Diagnostics must never stop CarPlay bring-up.
            }
        }
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

    private fun rotateIfNeeded() {
        if (!file.isFile || file.length() < MAX_BYTES) return
        val files = listOf(
            File(file.parentFile, "carplay-crv.previous.log"),
            File(file.parentFile, "carplay-crv.previous2.log"),
            File(file.parentFile, "carplay-crv.previous3.log"),
            File(file.parentFile, "carplay-crv.previous4.log"),
        )
        files.last().delete()
        for (index in files.lastIndex downTo 1) {
            if (files[index - 1].exists()) files[index - 1].renameTo(files[index])
        }
        file.renameTo(files[0])
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
        private const val MAX_BYTES = 8 * 1024 * 1024L
        private const val MAX_LINE = 2048
        private const val MAX_STACK_FRAMES = 12
        private const val DEDUPE_WINDOW_MILLIS = 1_000L
    }
}
