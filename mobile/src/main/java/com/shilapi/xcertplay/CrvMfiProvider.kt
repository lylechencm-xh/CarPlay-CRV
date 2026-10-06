package com.shilapi.xcertplay

import android.content.Context
import android.hardware.usb.UsbManager
import com.shilapi.xcertplay.mfi.MfiAuthenticationClient
import com.shilapi.xcertplay.mfi.MfiAuthenticator
import com.shilapi.xcertplay.orchestration.MfiRuntime
import com.shilapi.xcertplay.transport.Ch341DeviceMatcher
import com.shilapi.xcertplay.transport.Ch341I2cTransport
import com.shilapi.xcertplay.transport.Ch341UsbHost
import com.shilapi.xcertplay.transport.Ch341UsbSession
import com.shilapi.xcertplay.transport.I2cTransport
import com.shilapi.xcertplay.transport.I2cTransportException
import com.shilapi.xcertplay.transport.LinuxI2cTransport
import com.shilapi.xcertplay.transport.UsbDeviceId
import java.io.Closeable
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit

/**
 * CR-V MFi source selector.
 *
 * A CH341A in I2C/SPI/GPIO mode is preferred when it is physically present. The adapter uses the
 * public WCH USB identity 1a86:5512. If no such adapter is attached, the existing explicitly
 * provisioned local identity is used. A detected-but-broken CH341 path fails closed instead of
 * silently switching authentication sources.
 */
internal object CrvMfiProvider {
    data class Lease(
        val client: MfiAuthenticator,
        val source: String,
        private val closeable: Closeable?,
    ) : Closeable {
        override fun close() {
            closeable?.close()
        }
    }

    fun status(context: Context, usbManager: UsbManager): String {
        val host = host(context, usbManager)
        val device = host.discover().firstOrNull()
        return if (device != null) {
            "MFi source=CH341 usb=%04x:%04x".format(device.vendorId, device.productId)
        } else {
            val nodes = i2cNodes()
            if (nodes.isNotEmpty()) {
                "MFi source probe=native-i2c nodes=" + nodes.joinToString { it.name }
            } else {
                CrvMfiAssets.status(context) + " sourceFallback=local"
            }
        }
    }

    fun acquire(
        context: Context,
        usbManager: UsbManager,
        report: (String) -> Unit,
    ): Lease {
        val host = host(context, usbManager)
        val device = host.discover().firstOrNull()
        if (device == null) {
            report("MFi CH341 not detected")
            val native = acquireNativeI2c(report)
            if (native != null) return native
            report("No usable onboard MFi I2C coprocessor; trying local identity")
            return Lease(
                client = CrvMfiAssets.load(context),
                source = "local",
                closeable = null,
            )
        }

        report(
            "MFi CH341 detected usb=%04x:%04x".format(
                device.vendorId,
                device.productId,
            ),
        )
        awaitPermission(host, device, usbManager, report)
        val session = openBlocking(host, device)
        try {
            val transport = Ch341I2cTransport(session)
            report("MFi CH341 I2C bridge ready")
            val client = MfiRuntime.scan(transport)
            val protocolMajor = client.protocolMajor()
            val certificateBytes = client.readCertificate(MAX_CERTIFICATE_PROBE_BYTES).size
            report(
                "MFi CH341 coprocessor ready address=0x" +
                    client.address7Bit.toString(16) +
                    " protocolMajor=" + protocolMajor +
                    " certificateBytes=" + certificateBytes,
            )
            return Lease(
                client = client,
                source = "ch341",
                closeable = session,
            )
        } catch (error: Throwable) {
            session.close()
            throw error
        }
    }

    private fun acquireNativeI2c(report: (String) -> Unit): Lease? {
        val nodes = i2cNodes()
        if (nodes.isEmpty()) {
            report("Onboard I2C: no /dev/i2c-* nodes exposed")
            return null
        }
        report("Onboard I2C nodes=" + nodes.joinToString { it.absolutePath })
        for (node in nodes) {
            val access = "r=${node.canRead()} w=${node.canWrite()}"
            report("Probing ${node.absolutePath} $access")
            val transport = try {
                LinuxI2cTransport.open(node.absolutePath)
            } catch (error: Throwable) {
                report(
                    "I2C open failed ${node.name}: " +
                        "${error.javaClass.simpleName}: ${error.message.orEmpty()}",
                )
                continue
            }
            var keep = false
            try {
                for (address in MFI_ADDRESSES) {
                    val probe = probeNativeCandidate(transport, address)
                    report(
                        "I2C ${node.name} addr=0x${address.toString(16)} " +
                            (probe ?: "no MFi signature"),
                    )
                    if (probe != null) {
                        val client = MfiAuthenticationClient(transport, address)
                        keep = true
                        report(
                            "MFi onboard coprocessor selected bus=${node.name} " +
                                "address=0x${address.toString(16)}",
                        )
                        return Lease(
                            client = client,
                            source = "native-i2c:${node.name}:0x${address.toString(16)}",
                            closeable = transport,
                        )
                    }
                }
            } finally {
                if (!keep) runCatching { transport.close() }
            }
        }
        return null
    }

    /**
     * Non-authenticating MFi signature probe. Register selection writes only the register pointer;
     * it never writes challenge data or the authentication-control register.
     */
    private fun probeNativeCandidate(transport: I2cTransport, address: Int): String? {
        return try {
            val protocol = readRegister(transport, address, 0x02, 1)
            val certificateLength = readRegister(transport, address, 0x30, 2)
            if (protocol !in 1..0xff || certificateLength !in 1..MAX_CERTIFICATE_PROBE_BYTES) {
                null
            } else {
                "protocolMajor=$protocol certificateBytes=$certificateLength"
            }
        } catch (_: Throwable) {
            null
        }
    }

    private fun readRegister(
        transport: I2cTransport,
        address: Int,
        register: Int,
        length: Int,
    ): Int {
        transport.transaction(address, byteArrayOf(register.toByte()), 0)
        var value = 0
        for (byte in transport.transaction(address, ByteArray(0), length)) {
            value = (value shl 8) or (byte.toInt() and 0xff)
        }
        return value
    }

    private fun i2cNodes(): List<File> =
        File("/dev").listFiles()
            .orEmpty()
            .filter { it.isFile && I2C_NODE.matches(it.name) }
            .sortedBy { it.name.removePrefix("i2c-").toIntOrNull() ?: Int.MAX_VALUE }
            .take(MAX_I2C_NODES)

    private fun awaitPermission(
        host: Ch341UsbHost,
        device: android.hardware.usb.UsbDevice,
        usbManager: UsbManager,
        report: (String) -> Unit,
    ) {
        if (usbManager.hasPermission(device)) {
            report("MFi CH341 USB permission ready")
            return
        }

        val latch = CountDownLatch(1)
        var result: Ch341UsbHost.PermissionResult? = null
        val receiver = host.registerPermissionReceiver {
            result = it
            latch.countDown()
        }
        try {
            when (host.requestPermission(device)) {
                is Ch341UsbHost.PermissionRequest.AlreadyGranted -> {
                    report("MFi CH341 USB permission ready")
                    return
                }
                is Ch341UsbHost.PermissionRequest.Requested ->
                    report("Waiting for MFi CH341 USB permission")
            }
            if (!latch.await(PERMISSION_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)) {
                // Some OEM USB handlers grant access without delivering the permission broadcast.
                if (usbManager.hasPermission(device)) {
                    report("MFi CH341 USB permission ready (polled)")
                    return
                }
                throw I2cTransportException.PermissionDenied(
                    "MFi CH341 USB permission timed out",
                )
            }
            when (result) {
                is Ch341UsbHost.PermissionResult.Granted ->
                    report("MFi CH341 USB permission granted")
                is Ch341UsbHost.PermissionResult.Denied ->
                    throw I2cTransportException.PermissionDenied(
                        "MFi CH341 USB permission was denied",
                    )
                null -> throw I2cTransportException.PermissionDenied(
                    "MFi CH341 USB permission result was unavailable",
                )
            }
        } finally {
            runCatching { receiver.close() }
        }
    }

    private fun openBlocking(
        host: Ch341UsbHost,
        device: android.hardware.usb.UsbDevice,
    ): Ch341UsbSession {
        var result: Ch341UsbHost.OpenResult? = null
        host.openAsync(
            device,
            Executor { command -> command.run() },
        ) { result = it }
        return when (val opened = result) {
            is Ch341UsbHost.OpenResult.Connected -> opened.session
            is Ch341UsbHost.OpenResult.Failed -> throw opened.error
            null -> throw I2cTransportException.DeviceUnavailable(
                "MFi CH341 USB open did not complete",
            )
        }
    }

    private fun host(context: Context, usbManager: UsbManager): Ch341UsbHost =
        Ch341UsbHost(
            context = context,
            usbManager = usbManager,
            matcher = Ch341DeviceMatcher(
                listOf(UsbDeviceId(CH341_VENDOR_ID, CH341_I2C_PRODUCT_ID)),
            ),
        )

    private const val CH341_VENDOR_ID = 0x1a86
    private const val CH341_I2C_PRODUCT_ID = 0x5512
    private const val PERMISSION_TIMEOUT_MILLIS = 15_000L
    private val MFI_ADDRESSES = intArrayOf(0x10, 0x11)
    private val I2C_NODE = Regex("i2c-[0-9]+")
    private const val MAX_I2C_NODES = 16
    private const val MAX_CERTIFICATE_PROBE_BYTES = 1280
}
