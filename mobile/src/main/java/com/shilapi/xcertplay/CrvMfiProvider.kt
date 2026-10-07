package com.shilapi.xcertplay

import android.content.Context
import com.shilapi.xcertplay.mfi.MfiAuthenticationClient
import com.shilapi.xcertplay.mfi.MfiAuthenticator
import com.shilapi.xcertplay.mfi.MfiSelfCheck
import com.shilapi.xcertplay.mfi.MfiProtocolMajorResult
import com.shilapi.xcertplay.transport.LinuxI2cTransport
import java.io.Closeable
import java.io.File

/**
 * CR-V authentication source boundary.
 *
 * External CH341 bridges are intentionally unsupported for this target. The CR-V path records
 * board-visible I2C nodes for diagnostics, then uses an explicitly provisioned local MFi identity
 * until a verified Honda factory authentication service/HAL integration is implemented.
 */
internal object CrvMfiProvider {
    data class Lease(
        val client: MfiAuthenticator,
        val source: String,
        private val closeable: Closeable? = null,
    ) : Closeable {
        override fun close() {
            closeable?.close()
        }
    }

    fun status(context: Context): String {
        val identity = when (val raw = CrvMfiAssets.status(context)) {
            "MFi identity missing" -> "MFi local identity=absent"
            else -> raw.replace("MFi identity ", "MFi local identity=")
        }
        val configured = configuredI2cNode(context)
        val nodes = i2cNodes()
        return buildString {
            append(identity)
            append("; source=")
            append(if (configured != null) "onboard-i2c-preferred" else "local")
            if (configured != null) append("; onboard-i2c-configured=").append(configured)
            if (nodes.isNotEmpty()) {
                append("; onboard-i2c-visible=")
                append(nodes.joinToString { it.name })
            }
        }
    }

    fun acquire(
        context: Context,
        report: (String) -> Unit,
    ): Lease {
        reportOnboardI2cAvailability(report)
        configuredI2cNode(context)?.let { path ->
            val transport = try {
                LinuxI2cTransport.open(path)
            } catch (error: Exception) {
                report("MFi onboard I2C open failed node=$path type=${error.javaClass.simpleName}; falling back to local identity")
                null
            }
            if (transport != null) {
                try {
                    val selfCheck = MfiSelfCheck(transport).run()
                    val chip = selfCheck.chip
                    if (chip != null) {
                        val protocol = when (val result = chip.protocolMajor) {
                            is MfiProtocolMajorResult.Value -> result.major
                            is MfiProtocolMajorResult.MfiFailure -> null
                            is MfiProtocolMajorResult.TransportFailure -> null
                        }
                        report(
                            "MFi onboard I2C ready node=$path address=0x" +
                                chip.address7Bit.toString(16) +
                                " deviceVersion=0x" + chip.deviceVersion.toString(16) +
                                " protocolMajor=" + (protocol?.toString() ?: "unknown"),
                        )
                        return Lease(
                            client = MfiAuthenticationClient(transport, chip.address7Bit),
                            source = "onboard-i2c",
                            closeable = transport,
                        )
                    }
                    report("MFi onboard I2C node=$path has no authentication coprocessor at 0x10/0x11; falling back to local identity")
                } catch (error: Exception) {
                    report("MFi onboard I2C probe failed node=$path type=${error.javaClass.simpleName}; falling back to local identity")
                }
                runCatching { transport.close() }
            }
        }

        report("MFi source=local; external CH341 disabled for CR-V")
        return Lease(
            client = CrvMfiAssets.load(context),
            source = "local",
        )
    }

    private fun configuredI2cNode(context: Context): String? {
        val external = context.getExternalFilesDir(null) ?: return null
        val file = File(File(external, com.shilapi.xcertplay.mfi.LocalMfiAuthenticationClient.DIRECTORY), I2C_NODE_CONFIG)
        if (!file.isFile || file.length() !in 1..MAX_I2C_CONFIG_BYTES) return null
        val value = runCatching { file.readText(Charsets.US_ASCII).trim() }.getOrNull() ?: return null
        return value.takeIf { I2C_DEVICE_PATH.matches(it) }
    }

    private fun reportOnboardI2cAvailability(report: (String) -> Unit) {
        val nodes = i2cNodes()
        if (nodes.isEmpty()) {
            report("Onboard I2C: no /dev/i2c-* nodes exposed")
            return
        }
        report("Onboard I2C nodes=" + nodes.joinToString { it.absolutePath })
        for (node in nodes) {
            report(
                "Onboard I2C node ${node.name} visible " +
                    "r=${node.canRead()} w=${node.canWrite()} " +
                    "(metadata only; no register access)",
            )
        }
    }

    private fun i2cNodes(): List<File> =
        File("/dev").listFiles()
            .orEmpty()
            .filter { I2C_NODE.matches(it.name) }
            .sortedBy { it.name.removePrefix("i2c-").toIntOrNull() ?: Int.MAX_VALUE }
            .take(MAX_I2C_NODES)

    private val I2C_NODE = Regex("i2c-[0-9]+")
    private val I2C_DEVICE_PATH = Regex("/dev/i2c-[0-9]+")
    private const val I2C_NODE_CONFIG = "i2c-node.txt"
    private const val MAX_I2C_CONFIG_BYTES = 64L
    private const val MAX_I2C_NODES = 16
}
