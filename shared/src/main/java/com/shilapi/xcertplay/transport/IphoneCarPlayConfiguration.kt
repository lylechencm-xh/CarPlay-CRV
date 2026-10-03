package com.shilapi.xcertplay.transport

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.util.Log

/**
 * API19-safe discovery of the currently exposed CarPlay USB interfaces.
 *
 * Android 4.4 has no UsbConfiguration API. After Apple's 0x52 CarPlay transition the phone
 * re-enumerates; KitKat exposes the active configuration as a flat UsbDevice interface list.
 */
object IphoneCarPlayConfiguration {
    const val TAG = "xcertplay-usb"

    private const val USBMUX_CLASS = 0xff
    private const val USBMUX_SUBCLASS = 0xfe
    private const val USBMUX_PROTOCOL = 0x02
    private const val NCM_CONTROL_CLASS = 0x02
    private const val NCM_CONTROL_SUBCLASS = 0x0d

    private const val PREFERRED_USBMUX_OUT = 0x04
    private const val PREFERRED_USBMUX_IN = 0x85

    fun interfaces(device: UsbDevice): List<UsbInterface> =
        (0 until device.interfaceCount).map(device::getInterface)

    fun hasActiveCarPlayLayout(device: UsbDevice): Boolean {
        val result = usbMuxInterface(device) != null &&
            interfaces(device).any {
                it.interfaceClass == NCM_CONTROL_CLASS &&
                    it.interfaceSubclass == NCM_CONTROL_SUBCLASS
            }
        Log.i(TAG, "api19 active CarPlay layout=$result detail=${describe(device)}")
        return result
    }

    fun describe(device: UsbDevice): String =
        interfaces(device).joinToString(",") { intf ->
            "${intf.id}:${intf.interfaceClass.toString(16)}." +
                "${intf.interfaceSubclass.toString(16)}." +
                "${intf.interfaceProtocol.toString(16)}x${intf.endpointCount}"
        }

    fun usbMuxInterface(device: UsbDevice): UsbInterface? =
        interfaces(device).firstOrNull {
            it.interfaceClass == USBMUX_CLASS &&
                it.interfaceSubclass == USBMUX_SUBCLASS &&
                it.interfaceProtocol == USBMUX_PROTOCOL
        }

    fun usbMuxEndpoints(usbInterface: UsbInterface): Pair<UsbEndpoint, UsbEndpoint>? {
        val endpoints = (0 until usbInterface.endpointCount).map(usbInterface::getEndpoint)
        val out = endpoints.firstOrNull {
            it.address == PREFERRED_USBMUX_OUT &&
                it.direction == UsbConstants.USB_DIR_OUT &&
                it.type == UsbConstants.USB_ENDPOINT_XFER_BULK
        } ?: endpoints.singleOrNull {
            it.direction == UsbConstants.USB_DIR_OUT &&
                it.type == UsbConstants.USB_ENDPOINT_XFER_BULK
        }
        val input = endpoints.firstOrNull {
            it.address == PREFERRED_USBMUX_IN &&
                it.direction == UsbConstants.USB_DIR_IN &&
                it.type == UsbConstants.USB_ENDPOINT_XFER_BULK
        } ?: endpoints.singleOrNull {
            it.direction == UsbConstants.USB_DIR_IN &&
                it.type == UsbConstants.USB_ENDPOINT_XFER_BULK
        }
        return if (out != null && input != null) out to input else null
    }
}
