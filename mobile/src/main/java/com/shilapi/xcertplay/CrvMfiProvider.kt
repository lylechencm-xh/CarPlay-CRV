package com.shilapi.xcertplay

import android.content.Context
import com.shilapi.xcertplay.mfi.MfiAuthenticator
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
        val nodes = i2cNodes()
        return if (nodes.isNotEmpty()) {
            identity + "; source=local; onboard-i2c-visible=" + nodes.joinToString { it.name }
        } else {
            identity + "; source=local"
        }
    }

    fun acquire(
        context: Context,
        report: (String) -> Unit,
    ): Lease {
        reportOnboardI2cAvailability(report)
        report("MFi source=local; external CH341 disabled for CR-V")
        return Lease(
            client = CrvMfiAssets.load(context),
            source = "local",
        )
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
    private const val MAX_I2C_NODES = 16
}
