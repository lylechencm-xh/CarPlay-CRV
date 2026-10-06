package com.shilapi.xcertplay

import android.content.Context
import android.hardware.usb.UsbManager
import com.shilapi.xcertplay.mfi.MfiAuthenticator
import com.shilapi.xcertplay.orchestration.MfiRuntime
import com.shilapi.xcertplay.transport.Ch341DeviceMatcher
import com.shilapi.xcertplay.transport.Ch341I2cTransport
import com.shilapi.xcertplay.transport.Ch341UsbHost
import com.shilapi.xcertplay.transport.Ch341UsbSession
import com.shilapi.xcertplay.transport.I2cTransportException
import com.shilapi.xcertplay.transport.UsbDeviceId
import java.io.Closeable
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
            CrvMfiAssets.status(context) + " sourceFallback=local"
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
            report("MFi CH341 not detected; trying local identity")
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
    private const val MAX_CERTIFICATE_PROBE_BYTES = 1280
}
