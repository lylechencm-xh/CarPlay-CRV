package com.shilapi.xcertplay

import android.content.Context
import com.shilapi.xcertplay.mfi.LocalMfiAuthenticationClient
import java.io.File
import java.io.FileNotFoundException

/**
 * Loads an explicitly provisioned MFi accessory identity.
 *
 * No private key is committed to the repository. On Android 4.2.2 / API17 the user can place:
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
        val external = context.getExternalFilesDir(null)
            ?.let { File(it, LocalMfiAuthenticationClient.DIRECTORY) }

        // An explicitly provisioned external identity is an update request. Check it before the
        // installed copy so a replaced/revoked identity can be rotated without clearing app data.
        // installValidated() verifies the new pair before preserving and replacing the old one.
        if (external != null && hasCompleteIdentity(external)) {
            installValidated(external, target)
            val installed = LocalMfiAuthenticationClient.load(target)
            removeExternalProvisioningCopy(external)
            return installed
        }

        if (hasCompleteIdentity(target)) {
            return try {
                LocalMfiAuthenticationClient.load(target)
            } catch (installedFailure: Exception) {
                // A standalone APK can repair an interrupted/corrupt earlier installation from
                // its explicitly supplied assets. Ordinary source builds contain no such assets.
                if (!assetsContainIdentity(context)) throw installedFailure
                installFromAssets(context, target)
                LocalMfiAuthenticationClient.load(target)
            }
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

    fun status(context: Context): String {
        val externalDirectory = context.getExternalFilesDir(null)
            ?.let { File(it, LocalMfiAuthenticationClient.DIRECTORY) }
        if (externalDirectory != null && hasCompleteIdentity(externalDirectory)) {
            return if (runCatching { LocalMfiAuthenticationClient.load(externalDirectory) }.isSuccess) {
                "MFi identity valid source=external-update"
            } else {
                "MFi identity invalid source=external-update"
            }
        }

        val privateDirectory = File(context.filesDir, LocalMfiAuthenticationClient.DIRECTORY)
        if (hasCompleteIdentity(privateDirectory)) {
            return if (runCatching { LocalMfiAuthenticationClient.load(privateDirectory) }.isSuccess) {
                "MFi identity valid source=private"
            } else if (assetsContainIdentity(context)) {
                "MFi identity invalid source=private; repair=apk-assets"
            } else {
                "MFi identity invalid source=private"
            }
        }

        return if (assetsContainIdentity(context)) {
            "MFi identity present source=apk-assets"
        } else {
            "MFi identity missing"
        }
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
        val backup = File(parent, "${LocalMfiAuthenticationClient.DIRECTORY}-backup")
        staging.deleteRecursively()
        backup.deleteRecursively()
        if (!staging.mkdirs()) {
            throw IllegalStateException("Could not prepare MFi private staging directory")
        }
        restrictDirectory(staging)

        var movedOldTarget = false
        var installedNewTarget = false
        try {
            for (name in requiredFiles) {
                File(source, name).inputStream().use { input ->
                    File(staging, name).outputStream().use { output -> input.copyTo(output) }
                }
                restrictFile(File(staging, name))
            }
            LocalMfiAuthenticationClient.load(staging)

            if (target.exists()) {
                if (!target.renameTo(backup)) {
                    throw IllegalStateException("Could not preserve existing MFi identity")
                }
                movedOldTarget = true
            }
            if (!staging.renameTo(target)) {
                if (movedOldTarget) {
                    runCatching { backup.renameTo(target) }
                }
                throw IllegalStateException("Could not install MFi identity into private storage")
            }
            installedNewTarget = true
            restrictDirectory(target)
            requiredFiles.forEach { restrictFile(File(target, it)) }
            // Verify the final location before discarding the previous known-good copy.
            LocalMfiAuthenticationClient.load(target)
            backup.deleteRecursively()
        } catch (error: Throwable) {
            if (installedNewTarget) {
                target.deleteRecursively()
            }
            if (movedOldTarget && backup.exists() && !target.exists()) {
                runCatching { backup.renameTo(target) }
            }
            throw error
        } finally {
            staging.deleteRecursively()
            if (target.exists()) backup.deleteRecursively()
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
            restrictDirectory(it)
        }

    private fun restrictDirectory(directory: File) {
        directory.setReadable(false, false)
        directory.setWritable(false, false)
        directory.setExecutable(false, false)
        directory.setReadable(true, true)
        directory.setWritable(true, true)
        directory.setExecutable(true, true)
    }

    private fun restrictFile(file: File) {
        file.setReadable(false, false)
        file.setWritable(false, false)
        file.setExecutable(false, false)
        file.setReadable(true, true)
        file.setWritable(true, true)
    }

    private fun hasCompleteIdentity(directory: File): Boolean =
        directory.isDirectory && requiredFiles.all { name ->
            File(directory, name).let { it.isFile && it.length() > 0L }
        }

    private fun assetsContainIdentity(context: Context): Boolean =
        runCatching {
            val names = context.assets.list(LocalMfiAuthenticationClient.DIRECTORY).orEmpty().toSet()
            requiredFiles.all(names::contains)
        }.getOrElse { false }
}
