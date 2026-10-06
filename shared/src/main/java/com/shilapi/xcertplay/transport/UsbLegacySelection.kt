package com.shilapi.xcertplay.transport

import android.hardware.usb.UsbDeviceConnection
import android.util.Log

/**
 * Android 4.2.2 / API17 exposes the USB file descriptor but not the API21 Java
 * setConfiguration()/setInterface() methods. A raw endpoint-zero SET_CONFIGURATION changes the
 * iPhone but does not run Linux usb_set_configuration(), leaving usbcore/sysfs/driver bindings on
 * the old configuration. That split-brain state is fatal for Honda's kernel cdc_ncm path.
 *
 * Prefer the tiny usbfs JNI bridge so configuration and alternate-setting changes go through
 * USBDEVFS_SETCONFIGURATION / USBDEVFS_SETINTERFACE. Stock AOSP 4.2.2 does not register the later
 * native_set_configuration/native_set_interface framework methods.
 */
internal object UsbLegacySelection {
    fun setConfiguration(
        connection: UsbDeviceConnection,
        configurationValue: Int,
    ): Boolean {
        val result = UsbFsIoctl.setConfiguration(
            connection.fileDescriptor,
            configurationValue,
        )
        if (result == 0) return true
        if (result != UsbFsIoctl.UNAVAILABLE) {
            Log.w(
                IphoneCarPlayConfiguration.TAG,
                "USBDEVFS_SETCONFIGURATION cfg=$configurationValue failed errno=${-result}",
            )
            return false
        }

        // Host-side JVM tests do not package Android JNI libraries. Never use a raw EP0
        // SET_CONFIGURATION as the production Android fallback because it can desynchronize usbcore.
        if (isAndroidRuntime()) {
            Log.e(
                IphoneCarPlayConfiguration.TAG,
                "API17 usbfs JNI bridge unavailable; refusing raw SET_CONFIGURATION",
            )
            return false
        }
        return legacyTestSetConfiguration(connection, configurationValue)
    }

    fun setInterface(
        connection: UsbDeviceConnection,
        interfaceNumber: Int,
        alternateSetting: Int,
    ): Boolean {
        val result = UsbFsIoctl.setInterface(
            connection.fileDescriptor,
            interfaceNumber,
            alternateSetting,
        )
        if (result == 0) return true
        if (result != UsbFsIoctl.UNAVAILABLE) {
            Log.w(
                IphoneCarPlayConfiguration.TAG,
                "USBDEVFS_SETINTERFACE iface=$interfaceNumber/$alternateSetting failed errno=${-result}",
            )
            return false
        }

        if (isAndroidRuntime()) {
            Log.e(
                IphoneCarPlayConfiguration.TAG,
                "API17 usbfs JNI bridge unavailable; refusing raw SET_INTERFACE",
            )
            return false
        }
        return legacyTestSetInterface(connection, interfaceNumber, alternateSetting)
    }

    private fun isAndroidRuntime(): Boolean {
        val vm = System.getProperty("java.vm.name").orEmpty()
        return vm.contains("Dalvik", ignoreCase = true) ||
            vm.contains("ART", ignoreCase = true)
    }

    private fun legacyTestSetConfiguration(
        connection: UsbDeviceConnection,
        configurationValue: Int,
    ): Boolean =
        connection.controlTransfer(
            USB_DIR_OUT_STANDARD_DEVICE,
            USB_REQUEST_SET_CONFIGURATION,
            configurationValue,
            0,
            null,
            0,
            CONTROL_TIMEOUT_MILLIS,
        ) == 0

    private fun legacyTestSetInterface(
        connection: UsbDeviceConnection,
        interfaceNumber: Int,
        alternateSetting: Int,
    ): Boolean =
        connection.controlTransfer(
            USB_DIR_OUT_STANDARD_INTERFACE,
            USB_REQUEST_SET_INTERFACE,
            alternateSetting,
            interfaceNumber,
            null,
            0,
            CONTROL_TIMEOUT_MILLIS,
        ) == 0

    private const val USB_DIR_OUT_STANDARD_DEVICE = 0x00
    private const val USB_DIR_OUT_STANDARD_INTERFACE = 0x01
    private const val USB_REQUEST_SET_CONFIGURATION = 0x09
    private const val USB_REQUEST_SET_INTERFACE = 0x0b
    private const val CONTROL_TIMEOUT_MILLIS = 1_000
}
