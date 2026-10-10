package com.shilapi.xcertplay

import java.io.Closeable
import java.io.File

/** A shared timeline plus independently bounded subsystem histories. */
internal class CrvCategorizedLogStorage(
    directory: File,
    val file: File,
    categoryMaxBytes: Long = 256 * 1024L,
    categoryMaxFiles: Int = 4,
) : Closeable {
    private val lock = Any()
    private val overview = CrvLogStorage(directory, file)
    private val categories = CrvLogCategory.values().associateWith { category ->
        val folder = File(File(directory, "logs"), category.directoryName)
        CrvLogStorage(folder, File(folder, file.name), categoryMaxBytes, categoryMaxFiles)
    }

    fun append(line: String, category: CrvLogCategory, failure: Boolean, overviewEvent: Boolean) = synchronized(lock) {
        // Failure in one destination must not suppress all the others.
        runCatching { categories.getValue(category).append(line) }
        if (failure && category != CrvLogCategory.ERRORS) {
            runCatching { categories.getValue(CrvLogCategory.ERRORS).append(line) }
        }
        if (overviewEvent) runCatching { overview.append(line) }
    }

    fun pruneOldLogs() = synchronized(lock) {
        runCatching { overview.pruneOldLogs() }
        categories.values.forEach { runCatching { it.pruneOldLogs() } }
    }

    override fun close() = synchronized(lock) {
        overview.close()
        categories.values.forEach { it.close() }
    }
}
