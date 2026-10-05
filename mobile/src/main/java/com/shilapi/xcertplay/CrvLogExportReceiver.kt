package com.shilapi.xcertplay

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbManager
import com.shilapi.xcertplay.transport.IphoneUsbMatcher
import java.io.File

/**
 * Exports retained field diagnostics when Android finishes mounting removable storage.
 *
 * This receiver is manifest-registered so export still works after the CarPlay activity has left
 * the foreground. A mass-storage device is never sent through the iPhone path: only USB devices
 * with Apple's VID (0x05ac) are treated as an iPhone, and export is deferred while one is present.
 */
class CrvLogExportReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_MEDIA_MOUNTED) return

        val mountedRoot = intent.data?.path?.let(::File) ?: return
        val usbManager = context.getSystemService(Context.USB_SERVICE) as? UsbManager ?: return
        val appleMatcher = IphoneUsbMatcher.appleVendor()
        val appleDevicePresent = usbManager.deviceList.values.any { device ->
            appleMatcher.matches(device.vendorId, device.productId)
        }
        val diagnostics = CrvDiagnostics(context)
        if (appleDevicePresent) {
            diagnostics.log("removable storage export deferred while Apple USB device is attached")
            return
        }

        val pendingResult = goAsync()
        Thread({
            try {
                diagnostics.log("removable storage mounted; exporting retained diagnostics")
                var exported: File? = null
                var attempt = 0
                while (exported == null && attempt < EXPORT_ATTEMPTS) {
                    exported = diagnostics.exportToRemovableStorage(mountedRoot)
                    attempt++
                    if (exported == null && attempt < EXPORT_ATTEMPTS) {
                        Thread.sleep(EXPORT_RETRY_DELAY_MILLIS)
                    }
                }
                if (exported == null) {
                    diagnostics.log("removable storage export failed after $attempt attempts")
                } else {
                    diagnostics.log("diagnostics exported to ${exported.absolutePath}")
                }
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            } finally {
                pendingResult.finish()
            }
        }, "crv-log-export").start()
    }

    companion object {
        private const val EXPORT_ATTEMPTS = 3
        private const val EXPORT_RETRY_DELAY_MILLIS = 750L
    }
}
