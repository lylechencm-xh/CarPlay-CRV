package com.shilapi.xcertplay.transport

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface

/**
 * API17-safe descriptor discovery for the currently active iPhone USB layout.
 *
 * Android 4.2.2 exposes UsbDevice/UsbInterface but not UsbConfiguration.
 */
object IphoneCarPlayConfiguration {
    const val TAG = "xcertplay-usb"

    private const val USBMUX_CLASS = 0xff
    private const val USBMUX_SUBCLASS = 0xfe
    private const val USBMUX_PROTOCOL = 0x02
    private const val PREFERRED_USBMUX_OUT = 0x04
    private const val PREFERRED_USBMUX_IN = 0x85

    fun carPlayConfigurationValue(rawDescriptors: ByteArray): Int? {
        val candidates = UsbActiveConfiguration.configurationValues(rawDescriptors)
        return candidates.firstOrNull { value ->
            val descriptors = UsbActiveConfiguration.interfaces(rawDescriptors, value)
            hasUsbMux(descriptors) && hasCdcNcm(descriptors) && hasAppleEthernet(descriptors)
        } ?: candidates.firstOrNull { value ->
            val descriptors = UsbActiveConfiguration.interfaces(rawDescriptors, value)
            hasUsbMux(descriptors) && hasCdcNcm(descriptors)
        }
    }

    fun usbMuxInterface(device: UsbDevice): UsbInterface? =
        (0 until device.interfaceCount).map(device::getInterface).firstOrNull {
            it.interfaceClass == USBMUX_CLASS &&
                it.interfaceSubclass == USBMUX_SUBCLASS &&
                it.interfaceProtocol == USBMUX_PROTOCOL
        }

    fun usbMuxInterface(
        device: UsbDevice,
        rawDescriptors: ByteArray,
        activeConfigurationValue: Int?,
    ): UsbInterface? {
        if (activeConfigurationValue == null) return usbMuxInterface(device)
        val descriptor = UsbActiveConfiguration.interfaces(
            rawDescriptors,
            activeConfigurationValue,
        ).firstOrNull {
            it.interfaceClass == USBMUX_CLASS &&
                it.interfaceSubclass == USBMUX_SUBCLASS &&
                it.interfaceProtocol == USBMUX_PROTOCOL &&
                it.alternateSetting == 0 &&
                it.endpoints.count { endpoint ->
                    endpoint.type == UsbConstants.USB_ENDPOINT_XFER_BULK
                } >= 2
        } ?: return null
        return UsbActiveConfiguration.androidInterface(device, descriptor)
    }

    fun hasActiveCarPlayLayout(device: UsbDevice): Boolean =
        usbMuxInterface(device) != null && NcmFunctionDiscovery.find(device) != null

    fun hasActiveCarPlayLayout(
        device: UsbDevice,
        rawDescriptors: ByteArray,
        activeConfigurationValue: Int?,
    ): Boolean =
        usbMuxInterface(device, rawDescriptors, activeConfigurationValue) != null &&
            NcmFunctionDiscovery.find(device, rawDescriptors, activeConfigurationValue) != null

    fun hasActiveCarPlayLayout(device: UsbDevice, rawDescriptors: ByteArray): Boolean =
        hasActiveCarPlayLayout(device, rawDescriptors, null)

    private fun hasUsbMux(
        descriptors: List<UsbActiveConfiguration.InterfaceDescriptor>,
    ): Boolean = descriptors.any {
        it.interfaceClass == USBMUX_CLASS &&
            it.interfaceSubclass == USBMUX_SUBCLASS &&
            it.interfaceProtocol == USBMUX_PROTOCOL &&
            it.alternateSetting == 0 &&
            it.endpoints.count { endpoint ->
                endpoint.type == UsbConstants.USB_ENDPOINT_XFER_BULK
            } >= 2
    }

    private fun hasCdcNcm(
        descriptors: List<UsbActiveConfiguration.InterfaceDescriptor>,
    ): Boolean = descriptors.any {
        it.interfaceClass == NcmFunctionDiscovery.CONTROL_CLASS &&
            it.interfaceSubclass == NcmFunctionDiscovery.CONTROL_SUBCLASS &&
            it.alternateSetting == 0
    }

    private fun hasAppleEthernet(
        descriptors: List<UsbActiveConfiguration.InterfaceDescriptor>,
    ): Boolean = descriptors.any {
        it.interfaceClass == NcmFunctionDiscovery.APPLE_ETHERNET_CLASS &&
            it.interfaceSubclass == NcmFunctionDiscovery.APPLE_ETHERNET_SUBCLASS &&
            it.interfaceProtocol == NcmFunctionDiscovery.APPLE_ETHERNET_PROTOCOL
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
