package com.shilapi.xcertplay.transport

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDeviceConnection

/**
 * Android 4.2.2 / API17 does not expose UsbConfiguration.setConfiguration() or the later public
 * alternate-setting APIs. Use the USB standard requests directly through controlTransfer(), which
 * is available on API12+ and is the portable AOSP path on this baseline.
 *
 * Do not rely on private native_set_configuration/native_set_interface methods: stock AOSP 4.2.2
 * does not register them, and OEM-private methods are not required for the CR-V path.
 */
internal object UsbLegacySelection {
    fun setConfiguration(
        connection: UsbDeviceConnection,
        configurationValue: Int,
    ): Boolean =
        connection.controlTransfer(
            UsbConstants.USB_DIR_OUT or UsbConstants.USB_TYPE_STANDARD,
            USB_REQUEST_SET_CONFIGURATION,
            configurationValue,
            0,
            null,
            0,
            CONTROL_TIMEOUT_MILLIS,
        ) == 0

    fun setInterface(
        connection: UsbDeviceConnection,
        interfaceNumber: Int,
        alternateSetting: Int,
    ): Boolean =
        connection.controlTransfer(
            UsbConstants.USB_DIR_OUT or UsbConstants.USB_TYPE_STANDARD or USB_RECIP_INTERFACE,
            USB_REQUEST_SET_INTERFACE,
            alternateSetting,
            interfaceNumber,
            null,
            0,
            CONTROL_TIMEOUT_MILLIS,
        ) == 0

    private const val USB_RECIP_INTERFACE = 0x01
    private const val USB_REQUEST_SET_CONFIGURATION = 0x09
    private const val USB_REQUEST_SET_INTERFACE = 0x0b
    private const val CONTROL_TIMEOUT_MILLIS = 1_000
}
