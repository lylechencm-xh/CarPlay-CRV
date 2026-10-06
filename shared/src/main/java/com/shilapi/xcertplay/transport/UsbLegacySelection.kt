package com.shilapi.xcertplay.transport

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDeviceConnection

/**
 * Android 4.2.x exposes the native usbfs selectors in UsbDeviceConnection but not the public
 * UsbConfiguration/alternate-setting APIs added later. Prefer those hidden native methods so the
 * kernel USB host state stays synchronized; fall back to standard control requests only on OEM
 * builds that removed the AOSP methods.
 */
internal object UsbLegacySelection {
    fun setConfiguration(
        connection: UsbDeviceConnection,
        configurationValue: Int,
    ): Boolean {
        val native = invokeHiddenBoolean(
            connection,
            "native_set_configuration",
            arrayOf(Int::class.javaPrimitiveType!!),
            arrayOf(configurationValue),
        )
        if (native != null) return native
        return connection.controlTransfer(
            UsbConstants.USB_DIR_OUT or UsbConstants.USB_TYPE_STANDARD,
            USB_REQUEST_SET_CONFIGURATION,
            configurationValue,
            0,
            null,
            0,
            CONTROL_TIMEOUT_MILLIS,
        ) == 0
    }

    fun setInterface(
        connection: UsbDeviceConnection,
        interfaceNumber: Int,
        alternateSetting: Int,
    ): Boolean {
        val native = invokeHiddenBoolean(
            connection,
            "native_set_interface",
            arrayOf(Int::class.javaPrimitiveType!!, Int::class.javaPrimitiveType!!),
            arrayOf(interfaceNumber, alternateSetting),
        )
        if (native != null) return native
        return connection.controlTransfer(
            UsbConstants.USB_DIR_OUT or UsbConstants.USB_TYPE_STANDARD or USB_RECIP_INTERFACE,
            USB_REQUEST_SET_INTERFACE,
            alternateSetting,
            interfaceNumber,
            null,
            0,
            CONTROL_TIMEOUT_MILLIS,
        ) == 0
    }

    private fun invokeHiddenBoolean(
        connection: UsbDeviceConnection,
        name: String,
        parameterTypes: Array<Class<*>>,
        args: Array<Any>,
    ): Boolean? = try {
        val method = UsbDeviceConnection::class.java.getDeclaredMethod(name, *parameterTypes)
        method.isAccessible = true
        method.invoke(connection, *args) as? Boolean
    } catch (_: NoSuchMethodException) {
        null
    } catch (_: IllegalAccessException) {
        null
    } catch (_: java.lang.reflect.InvocationTargetException) {
        null
    } catch (_: SecurityException) {
        null
    }

    private const val USB_RECIP_INTERFACE = 0x01
    private const val USB_REQUEST_SET_CONFIGURATION = 0x09
    private const val USB_REQUEST_SET_INTERFACE = 0x0b
    private const val CONTROL_TIMEOUT_MILLIS = 1_000
}
