package com.shilapi.xcertplay

import android.content.Context
import com.shilapi.xcertplay.mfi.LocalMfiAuthenticationClient
import java.io.File

object CrvMfiAssets {
    @Synchronized
    fun load(context: Context): LocalMfiAuthenticationClient {
        val target = File(context.filesDir, LocalMfiAuthenticationClient.DIRECTORY)
        if (!target.isDirectory) {
            val staging = File(context.filesDir, "offline-mfi-staging")
            staging.deleteRecursively()
            check(staging.mkdirs()) { "Could not prepare local MFi authentication" }
            try {
                for (name in listOf("identity.pk8", "certificate.p7b")) {
                    val out = File(staging, name)
                    context.assets.open("offline-mfi/$name").use { input ->
                        out.outputStream().use { output -> input.copyTo(output) }
                    }
                }
                // Validate before installing.
                LocalMfiAuthenticationClient.load(staging)
                check(staging.renameTo(target)) { "Could not install local MFi authentication" }
            } finally {
                staging.deleteRecursively()
            }
        }
        return LocalMfiAuthenticationClient.load(target)
    }
}
