package com.shilapi.xcertplay.transport

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface

/**
 * Finds the NCM control/data interface pair inside an active iPhone configuration.
 *
 * LIVI claims the control interface, claims the data interface, and selects data alternate
 * setting 1, the setting that carries the bulk endpoints. This class only reads descriptors.
 */
object NcmFunctionDiscovery {
    const val CONTROL_CLASS = 0x02
    const val CONTROL_SUBCLASS = 0x0d
    const val DATA_CLASS = 0x0a
    const val APPLE_ETHERNET_CLASS = 0xff
    const val APPLE_ETHERNET_SUBCLASS = 0xfd
    const val APPLE_ETHERNET_PROTOCOL = 0x01
    const val DATA_ALTERNATE_SETTING = 1

    data class NcmFunction(
        val control: UsbInterface,
        val data: UsbInterface,
        val dataAlternateSetting: Int,
        val statusIn: UsbEndpoint?,
        val bulkIn: UsbEndpoint,
        val bulkOut: UsbEndpoint,
    )

    fun find(device: android.hardware.usb.UsbDevice): NcmFunction? {
        val interfaces = (0 until device.interfaceCount).map(device::getInterface)
        return findCdcNcm(interfaces, null)
    }

    fun find(
        device: android.hardware.usb.UsbDevice,
        rawDescriptors: ByteArray,
    ): NcmFunction? {
        val interfaces = (0 until device.interfaceCount).map(device::getInterface)
        return findCdcNcm(interfaces, rawDescriptors)
    }

    private fun findCdcNcm(
        interfaces: List<UsbInterface>,
        rawDescriptors: ByteArray?,
    ): NcmFunction? {
        val control = interfaces.firstOrNull {
            it.interfaceClass == CONTROL_CLASS && it.interfaceSubclass == CONTROL_SUBCLASS
        } ?: return null
        val candidates = interfaces
            .filter { it.interfaceClass == DATA_CLASS && bulkEndpoints(it) != null }
        val data = candidates.minByOrNull {
            if (alternateSetting(rawDescriptors, it) == DATA_ALTERNATE_SETTING) 0 else 1
        } ?: return null
        val dataAlternateSetting = alternateSetting(rawDescriptors, data) ?: DATA_ALTERNATE_SETTING
        val endpoints = bulkEndpoints(data) ?: return null
        val statusIn = (0 until control.endpointCount)
            .map(control::getEndpoint)
            .singleOrNull {
                it.direction == UsbConstants.USB_DIR_IN &&
                    it.type == UsbConstants.USB_ENDPOINT_XFER_INT
            }
        return NcmFunction(
            control = control,
            data = data,
            dataAlternateSetting = dataAlternateSetting,
            statusIn = statusIn,
            bulkIn = endpoints.first,
            bulkOut = endpoints.second,
        )
    }

    private fun alternateSetting(raw: ByteArray?, usbInterface: UsbInterface): Int? {
        if (raw == null) return null
        var offset = 0
        var fallback: Int? = null
        while (offset + 2 <= raw.size) {
            val length = raw[offset].toInt() and 0xff
            val type = raw[offset + 1].toInt() and 0xff
            if (length < 2 || offset + length > raw.size) return fallback
            if (type == USB_INTERFACE_DESCRIPTOR_TYPE && length >= USB_INTERFACE_DESCRIPTOR_LENGTH) {
                val number = raw[offset + 2].toInt() and 0xff
                val alternate = raw[offset + 3].toInt() and 0xff
                val endpointCount = raw[offset + 4].toInt() and 0xff
                val interfaceClass = raw[offset + 5].toInt() and 0xff
                val interfaceSubclass = raw[offset + 6].toInt() and 0xff
                val interfaceProtocol = raw[offset + 7].toInt() and 0xff
                if (
                    number == usbInterface.id &&
                    interfaceClass == usbInterface.interfaceClass &&
                    interfaceSubclass == usbInterface.interfaceSubclass &&
                    interfaceProtocol == usbInterface.interfaceProtocol &&
                    endpointCount == usbInterface.endpointCount
                ) {
                    if (alternate == DATA_ALTERNATE_SETTING) return alternate
                    if (fallback == null) fallback = alternate
                }
            }
            offset += length
        }
        return fallback
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

    private const val USB_INTERFACE_DESCRIPTOR_TYPE = 0x04
    private const val USB_INTERFACE_DESCRIPTOR_LENGTH = 9
}
