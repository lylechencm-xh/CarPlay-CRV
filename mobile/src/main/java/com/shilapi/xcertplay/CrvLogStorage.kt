package com.shilapi.xcertplay

import java.io.File
import java.io.FileOutputStream

/**
 * Bounds CR-V field logs without touching files that do not match our session naming scheme.
 * The current session keeps its newest two segments; older sessions share the remaining budget.
 */
internal class CrvLogStorage(
    private val directory: File,
    val file: File,
    private val maxFileBytes: Long = MAX_FILE_BYTES,
    private val maxFiles: Int = MAX_FILES,
) {
    private val lock = Any()
    private val previousFile = File(directory, file.name.removeSuffix(".log") + "-previous.log")

    init {
        require(maxFileBytes > 0L)
        require(maxFiles >= 2)
    }

    fun append(line: String) = synchronized(lock) {
        val bytes = (line + "\n").toByteArray(Charsets.UTF_8)
        if (bytes.size.toLong() > maxFileBytes) return@synchronized
        if (!directory.exists() && !directory.mkdirs()) return@synchronized
        if (file.length() + bytes.size > maxFileBytes && !rotate()) return@synchronized
        FileOutputStream(file, true).use { it.write(bytes) }
    }

    /** Reserve room for the active file and remove only older CR-V session logs. */
    fun pruneOldLogs() = synchronized(lock) {
        val canonicalDirectory = runCatching { directory.canonicalFile }.getOrNull()
            ?: return@synchronized
        val files = directory.listFiles()?.filter { candidate ->
            candidate.isFile &&
                SESSION_LOG_NAME.matches(candidate.name) &&
                runCatching { candidate.canonicalFile.parentFile == canonicalDirectory }
                    .getOrDefault(false)
        }.orEmpty()

        val reserveSlots = 1 + if (previousFile.exists()) 1 else 0
        var remainingSlots = (maxFiles - reserveSlots).coerceAtLeast(0)
        var remainingBytes = maxFileBytes * remainingSlots
        files.asSequence()
            .filter { it != file && it != previousFile }
            .sortedWith(compareByDescending<File> { it.lastModified() }.thenByDescending { it.name })
            .forEach { candidate ->
                if (remainingSlots > 0 && candidate.length() <= remainingBytes) {
                    remainingSlots--
                    remainingBytes -= candidate.length()
                } else {
                    candidate.delete()
                }
            }
    }

    private fun rotate(): Boolean {
        if (previousFile.exists() && !previousFile.delete()) return false
        if (!file.renameTo(previousFile)) return false
        pruneOldLogs()
        return true
    }

    companion object {
        const val MAX_FILE_BYTES = 512 * 1024L
        const val MAX_FILES = 8
        private val SESSION_LOG_NAME = Regex(
            """^carplay-crv-v[A-Za-z0-9._-]+-\d{8}-\d{6}-\d{3}-p\d+-b\d+(?:-previous)?\.log$""",
        )
    }
}
