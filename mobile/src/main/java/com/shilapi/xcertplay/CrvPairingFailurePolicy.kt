package com.shilapi.xcertplay

/** Only an explicit Lockdown rejection invalidates the persisted pair record. */
internal object CrvPairingFailurePolicy {
    fun isPairRejection(error: Throwable): Boolean {
        val visited = mutableSetOf<Throwable>()
        var cause: Throwable? = error
        while (cause != null && visited.add(cause)) {
            val message = cause.message.orEmpty()
            if (message.startsWith("Lockdown StartSession failed:") &&
                message.substringAfter(':').trim() in setOf("InvalidHostID", "InvalidPairRecord")
            ) return true
            cause = cause.cause
        }
        return false
    }
}
