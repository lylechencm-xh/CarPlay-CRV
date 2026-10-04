package com.shilapi.xcertplay

import android.content.Context
import com.shilapi.xcertplay.mfi.LocalMfiAuthenticationClient
import java.io.File
import java.io.FileNotFoundException

/**
 * Loads an explicitly provisioned MFi accessory identity.
 *
 * No private key is committed to the repository. On Android 4.4 the user can place:
 *   identity.pk8
 *   certificate.p7b
 * under the app external-files/offline-mfi directory. The files are validated and then copied
 * into app-private storage before use.
 */
object CrvMfiAssets {
    private val requiredFiles = listOf("identity.pk8", "certificate.p7b")

    @Synchronized
    fun load(context: Context): LocalMfiAuthenticationClient {
        val target = File(context.filesDir, LocalMfiAuthenticationClient.DIRECTORY)

        if (hasCompleteIdentity(target)) {
            return LocalMfiAuthenticationClient.load(target)
        }

        val external = context.getExternalFilesDir(null)
            ?.let { File(it, LocalMfiAuthenticationClient.DIRECTORY) }

        if (external != null && hasCompleteIdentity(external)) {
            installValidated(external, target)
            val installed = LocalMfiAuthenticationClient.load(target)
            removeExternalProvisioningCopy(external)
            return installed
        }

        // Standalone builds may explicitly inject the two files into APK assets at build time.
        if (assetsContainIdentity(context)) {
            installFromAssets(context, target)
            return LocalMfiAuthenticationClient.load(target)
        }

        val expected = external?.absolutePath
            ?: "Android/data/${context.packageName}/files/${LocalMfiAuthenticationClient.DIRECTORY}"
        throw FileNotFoundException(
            "MFi identity missing. Copy identity.pk8 and certificate.p7b to $expected",
        )
    }

    fun provisioningDirectory(context: Context): String =
        context.getExternalFilesDir(null)
            ?.let { File(it, LocalMfiAuthenticationClient.DIRECTORY).absolutePath }
            ?: "Android/data/${context.packageName}/files/${LocalMfiAuthenticationClient.DIRECTORY}"

    private fun installFromAssets(context: Context, target: File) {
        val staging = stagingDirectory(context)
        try {
            for (name in requiredFiles) {
                val out = File(staging, name)
                context.assets.open("${LocalMfiAuthenticationClient.DIRECTORY}/$name").use { input ->
                    out.outputStream().use { output -> input.copyTo(output) }
                }
            }
            installValidated(staging, target)
        } finally {
            staging.deleteRecursively()
        }
    }

    private fun installValidated(source: File, target: File) {
        // Validate the source before copying private material.
        LocalMfiAuthenticationClient.load(source)

        val parent = target.parentFile
            ?: throw IllegalStateException("MFi private directory has no parent")
        if (!parent.isDirectory && !parent.mkdirs()) {
            throw IllegalStateException("Could not create MFi private parent directory")
        }

        val staging = File(parent, "${LocalMfiAuthenticationClient.DIRECTORY}-install")
        staging.deleteRecursively()
        if (!staging.mkdirs()) {
            throw IllegalStateException("Could not prepare MFi private staging directory")
        }

        try {
            for (name in requiredFiles) {
                File(source, name).inputStream().use { input ->
                    File(staging, name).outputStream().use { output -> input.copyTo(output) }
                }
            }
            LocalMfiAuthenticationClient.load(staging)

            target.deleteRecursively()
            if (!staging.renameTo(target)) {
                throw IllegalStateException("Could not install MFi identity into private storage")
            }
        } finally {
            staging.deleteRecursively()
        }
    }

    private fun removeExternalProvisioningCopy(directory: File) {
        for (name in requiredFiles) {
            runCatching { File(directory, name).delete() }
        }
        runCatching { directory.delete() }
    }

    private fun stagingDirectory(context: Context): File =
        File(context.filesDir, "${LocalMfiAuthenticationClient.DIRECTORY}-asset-staging").also {
            it.deleteRecursively()
            if (!it.mkdirs()) throw IllegalStateException("Could not prepare local MFi authentication")
        }

    private fun hasCompleteIdentity(directory: File): Boolean =
        directory.isDirectory && requiredFiles.all { name ->
            File(directory, name).let { it.isFile && it.length() > 0L }
        }

    private fun assetsContainIdentity(context: Context): Boolean =
        runCatching {
            val names = context.assets.list(LocalMfiAuthenticationClient.DIRECTORY).orEmpty().toSet()
            requiredFiles.all(names::contains)
        }.getOrDefault(false)
}
