package com.shilapi.xcertplay.transport

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface

/** API19-safe discovery of the iPhone CDC-NCM interfaces exposed after CarPlay re-enumeration. */
object NcmFunctionDiscovery {
    const val CONTROL_CLASS = 0x02
    const val CONTROL_SUBCLASS = 0x0d
    const val DATA_CLASS = 0x0a
    const val DATA_ALTERNATE_SETTING = 1

    data class NcmFunction(
        val control: UsbInterface,
        val data: UsbInterface,
        val statusIn: UsbEndpoint?,
        val bulkIn: UsbEndpoint,
        val bulkOut: UsbEndpoint,
        val dataAlternateSetting: Int = DATA_ALTERNATE_SETTING,
    )

    fun find(device: UsbDevice): NcmFunction? {
        val interfaces = IphoneCarPlayConfiguration.interfaces(device)
        val control = interfaces.firstOrNull {
            it.interfaceClass == CONTROL_CLASS && it.interfaceSubclass == CONTROL_SUBCLASS
        } ?: return null

        // Android 4.4 exposes UsbInterface without an alternate-setting accessor. Prefer the
        // same interface number that has the NCM bulk endpoint pair; Apple's data alt is 1.
        val data = interfaces.firstOrNull {
            it.interfaceClass == DATA_CLASS && bulkEndpoints(it) != null
        } ?: return null

        val endpoints = bulkEndpoints(data) ?: return null
        val statusIn = (0 until control.endpointCount)
            .map(control::getEndpoint)
            .singleOrNull {
                it.direction == UsbConstants.USB_DIR_IN &&
                    it.type == UsbConstants.USB_ENDPOINT_XFER_INT
            }

        return NcmFunction(control, data, statusIn, endpoints.first, endpoints.second)
    }

    private fun bulkEndpoints(usbInterface: UsbInterface): Pair<UsbEndpoint, UsbEndpoint>? {
        val endpoints = (0 until usbInterface.endpointCount).map(usbInterface::getEndpoint)
        val input = endpoints.singleOrNull {
            it.direction == UsbConstants.USB_DIR_IN && it.type == UsbConstants.USB_ENDPOINT_XFER_BULK
        }
        val output = endpoints.singleOrNull {
            it.direction == UsbConstants.USB_DIR_OUT && it.type == UsbConstants.USB_ENDPOINT_XFER_BULK
        }
        return if (input != null && output != null) input to output else null
    }
}
