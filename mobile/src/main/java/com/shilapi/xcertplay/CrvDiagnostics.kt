package com.shilapi.xcertplay

import android.content.Context
import android.os.Environment
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * API19-safe field log for in-car testing.
 *
 * Logs are always persisted locally first. After the iPhone is unplugged, inserting a writable
 * USB disk allows the activity to call [exportToRemovableStorage] and copy the retained logs to
 * CarPlay-CRV/logs without requiring the phone and disk to be connected at the same time.
 *
 * Only stage/status messages are recorded. MFi private keys, certificates, Lockdown keys and
 * raw protocol payloads are never written here.
 */
class CrvDiagnostics(context: Context) {
    private val lock = Any()
    private val appContext = context.applicationContext
    private val directory = context.getExternalFilesDir(null) ?: context.filesDir
    private val file = File(directory, "carplay-crv.log")
    private val formatter = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
    private val exportFormatter = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US)

    fun path(): String = file.absolutePath

    fun log(message: String) {
        val safe = sanitize(message)
        synchronized(lock) {
            try {
                rotateIfNeeded()
                file.parentFile?.mkdirs()
                // appendText opens/writes/closes for each line, so each diagnostic is flushed to
                // storage immediately instead of being held in a process buffer.
                file.appendText("${formatter.format(Date())}  $safe\n")
            } catch (_: Exception) {
                // Diagnostics must never stop CarPlay bring-up.
            }
        }
    }

    /**
     * Best-effort export to a removable USB mass-storage mount.
     * Returns the exported directory, or null when no writable removable mount is visible.
     */
    fun exportToRemovableStorage(): File? = synchronized(lock) {
        try {
            val root = findWritableRemovableRoot() ?: return@synchronized null
            val output = File(root, "CarPlay-CRV/logs")
            if (!output.exists() && !output.mkdirs()) return@synchronized null
            if (!output.isDirectory || !output.canWrite()) return@synchronized null

            val stamp = exportFormatter.format(Date())
            if (file.isFile) file.copyTo(File(output, "crv-carplay-$stamp.log"), overwrite = true)
            val previous = File(directory, "carplay-crv.previous.log")
            if (previous.isFile) {
                previous.copyTo(File(output, "crv-carplay-$stamp-previous.log"), overwrite = true)
            }
            File(output, "README.txt").writeText(
                "CarPlay-CRV diagnostic export\n" +
                    "Exported: ${formatter.format(Date())}\n" +
                    "Internal source: ${file.absolutePath}\n"
            )
            output
        } catch (_: Exception) {
            null
        }
    }

    private fun findWritableRemovableRoot(): File? {
        val internalRoots = HashSet<String>()
        fun remember(file: File?) {
            if (file == null) return
            try { internalRoots.add(file.canonicalPath) } catch (_: Exception) { }
        }
        remember(Environment.getExternalStorageDirectory())
        remember(directory)
        remember(appContext.filesDir)

        // Honda/Android 4.x vendors use several mount roots. We inspect their children instead of
        // hard-coding one USB path, and reject the app/internal storage roots above.
        val parents = listOf(File("/storage"), File("/mnt"), File("/media"))
        val candidates = ArrayList<File>()
        for (parent in parents) {
            val children = try { parent.listFiles() } catch (_: Exception) { null } ?: continue
            for (child in children) {
                if (child.isDirectory) candidates.add(child)
                val grandchildren = try { child.listFiles() } catch (_: Exception) { null }
                grandchildren?.filterTo(candidates) { it.isDirectory }
            }
        }

        return candidates.firstOrNull { candidate ->
            val canonical = try { candidate.canonicalPath } catch (_: Exception) { return@firstOrNull false }
            if (internalRoots.any { canonical == it || canonical.startsWith("$it/") }) return@firstOrNull false
            if (!candidate.canWrite()) return@firstOrNull false
            val probe = File(candidate, ".carplay-crv-write-test")
            try {
                probe.writeText("ok")
                probe.delete()
                true
            } catch (_: Exception) {
                false
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
    }
}
