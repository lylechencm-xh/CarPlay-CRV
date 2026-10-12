package com.shilapi.xcertplay.transport

/** Retry a refused USB operation briefly without changing its kernel-driver claim policy. */
internal object NcmInterfaceRetry {
    fun run(
        attempts: Int = 5,
        sleep: (Long) -> Unit = Thread::sleep,
        onAttempt: (Int, Boolean) -> Unit = { _, _ -> },
        operation: () -> Boolean,
    ): Boolean {
        require(attempts > 0)
        for (attempt in 1..attempts) {
            if (Thread.currentThread().isInterrupted) throw InterruptedException("NCM interface setup cancelled")
            val success = operation()
            onAttempt(attempt, success)
            if (success) return true
            if (attempt < attempts) {
                try {
                    sleep(100L)
                } catch (error: InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw error
                }
            }
        }
        return false
    }
}
