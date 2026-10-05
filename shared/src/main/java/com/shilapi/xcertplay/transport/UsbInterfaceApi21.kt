package com.shilapi.xcertplay.transport

import android.annotation.TargetApi
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbInterface

@TargetApi(21)
internal object UsbInterfaceApi21 {
    fun select(connection: UsbDeviceConnection, usbInterface: UsbInterface): Boolean =
        connection.setInterface(usbInterface)
}
