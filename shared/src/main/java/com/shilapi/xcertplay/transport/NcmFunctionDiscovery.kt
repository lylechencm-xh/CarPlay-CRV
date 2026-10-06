package com.shilapi.xcertplay.transport

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface

/**
 * Finds the NCM control/data interface pair inside an active iPhone configuration.
 *
 * Android 4.2.x exposes a flattened UsbDevice interface list, so API17 callers should use the
 * overload that supplies raw descriptors plus the active bConfigurationValue. That path scopes
 * discovery to one configuration and follows the CDC Union descriptor to the matching data
 * interface instead of pairing unrelated duplicate interface numbers from other configurations.
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
        val configurationValue: Int?,
        val control: UsbInterface,
        val data: UsbInterface,
        val dataAlternateSetting: Int,
        val statusIn: UsbEndpoint?,
        val bulkIn: UsbEndpoint,
        val bulkOut: UsbEndpoint,
    )

    /** Compatibility fallback for callers that cannot inspect the active configuration. */
    @Deprecated("Production callers must scope discovery to the active USB configuration")
    fun find(device: UsbDevice): NcmFunction? {
        val interfaces = (0 until device.interfaceCount).map(device::getInterface)
        return findFlattened(interfaces)
    }

    /** Compatibility fallback. Prefer the active-configuration overload on API17. */
    @Deprecated("Production callers must supply the active USB configuration value")
    fun find(device: UsbDevice, rawDescriptors: ByteArray): NcmFunction? =
        find(device, rawDescriptors, null)

    fun find(
        device: UsbDevice,
        rawDescriptors: ByteArray,
        activeConfigurationValue: Int?,
    ): NcmFunction? {
        if (activeConfigurationValue == null) {
            return findFlattened((0 until device.interfaceCount).map(device::getInterface))
        }

        val pair = descriptorPair(rawDescriptors, activeConfigurationValue) ?: return null
        val controlDescriptor = pair.first
        val dataDescriptor = pair.second
        val control = UsbActiveConfiguration.androidInterface(device, controlDescriptor)
            ?: return null
        val data = UsbActiveConfiguration.androidInterface(device, dataDescriptor)
            ?: return null
        val endpoints = bulkEndpoints(data, dataDescriptor) ?: return null
        val statusIn = (0 until control.endpointCount)
            .map(control::getEndpoint)
            .singleOrNull {
                it.direction == UsbConstants.USB_DIR_IN &&
                    it.type == UsbConstants.USB_ENDPOINT_XFER_INT
            }

        return NcmFunction(
            configurationValue = activeConfigurationValue,
            control = control,
            data = data,
            dataAlternateSetting = dataDescriptor.alternateSetting,
            statusIn = statusIn,
            bulkIn = endpoints.first,
            bulkOut = endpoints.second,
        )
    }

    internal fun descriptorPair(
        rawDescriptors: ByteArray,
        activeConfigurationValue: Int,
    ): Pair<UsbActiveConfiguration.InterfaceDescriptor, UsbActiveConfiguration.InterfaceDescriptor>? {
        val descriptors = UsbActiveConfiguration.interfaces(
            rawDescriptors,
            activeConfigurationValue,
        )
        val controls = descriptors.filter {
            it.interfaceClass == CONTROL_CLASS &&
                it.interfaceSubclass == CONTROL_SUBCLASS &&
                it.alternateSetting == 0
        }
        for (control in controls) {
            val unionDataNumber = unionSlaveInterface(control)
            val bulkData = descriptors
                .filter { descriptor ->
                    descriptor.interfaceClass == DATA_CLASS && hasBulkPair(descriptor)
                }

            val dataCandidates = if (unionDataNumber != null) {
                bulkData.filter { it.number == unionDataNumber }
            } else {
                val distinctNumbers = bulkData.map { it.number }.distinct()
                if (distinctNumbers.size != 1) continue
                bulkData
            }

            val data = dataCandidates.minByOrNull {
                if (it.alternateSetting == DATA_ALTERNATE_SETTING) 0 else 1
            } ?: continue
            return control to data
        }
        return null
    }
    private fun findFlattened(interfaces: List<UsbInterface>): NcmFunction? {
        val control = interfaces.firstOrNull {
            it.interfaceClass == CONTROL_CLASS && it.interfaceSubclass == CONTROL_SUBCLASS
        } ?: return null
        val data = interfaces.firstOrNull {
            it.interfaceClass == DATA_CLASS && bulkEndpoints(it, null) != null
        } ?: return null
        val endpoints = bulkEndpoints(data, null) ?: return null
        val statusIn = (0 until control.endpointCount)
            .map(control::getEndpoint)
            .singleOrNull {
                it.direction == UsbConstants.USB_DIR_IN &&
                    it.type == UsbConstants.USB_ENDPOINT_XFER_INT
            }
        return NcmFunction(
            configurationValue = null,
            control = control,
            data = data,
            dataAlternateSetting = DATA_ALTERNATE_SETTING,
            statusIn = statusIn,
            bulkIn = endpoints.first,
            bulkOut = endpoints.second,
        )
    }

    private fun unionSlaveInterface(
        control: UsbActiveConfiguration.InterfaceDescriptor,
    ): Int? {
        for (descriptor in control.extraDescriptors) {
            if (
                descriptor.size >= 5 &&
                (descriptor[1].toInt() and 0xff) == CDC_FUNCTIONAL_DESCRIPTOR_TYPE &&
                (descriptor[2].toInt() and 0xff) == CDC_UNION_SUBTYPE &&
                (descriptor[3].toInt() and 0xff) == control.number
            ) {
                return descriptor[4].toInt() and 0xff
            }
        }
        return null
    }

    private fun hasBulkPair(
        descriptor: UsbActiveConfiguration.InterfaceDescriptor,
    ): Boolean {
        val input = descriptor.endpoints.count {
            it.direction == UsbConstants.USB_DIR_IN &&
                it.type == UsbConstants.USB_ENDPOINT_XFER_BULK
        }
        val output = descriptor.endpoints.count {
            it.direction == UsbConstants.USB_DIR_OUT &&
                it.type == UsbConstants.USB_ENDPOINT_XFER_BULK
        }
        return input == 1 && output == 1
    }

    private fun bulkEndpoints(
        usbInterface: UsbInterface,
        descriptor: UsbActiveConfiguration.InterfaceDescriptor?,
    ): Pair<UsbEndpoint, UsbEndpoint>? {
        val endpoints = (0 until usbInterface.endpointCount).map(usbInterface::getEndpoint)
        val wantedIn = descriptor?.endpoints?.singleOrNull {
            it.direction == UsbConstants.USB_DIR_IN &&
                it.type == UsbConstants.USB_ENDPOINT_XFER_BULK
        }?.address
        val wantedOut = descriptor?.endpoints?.singleOrNull {
            it.direction == UsbConstants.USB_DIR_OUT &&
                it.type == UsbConstants.USB_ENDPOINT_XFER_BULK
        }?.address

        val input = endpoints.singleOrNull {
            it.direction == UsbConstants.USB_DIR_IN &&
                it.type == UsbConstants.USB_ENDPOINT_XFER_BULK &&
                (wantedIn == null || it.address == wantedIn)
        }
        val output = endpoints.singleOrNull {
            it.direction == UsbConstants.USB_DIR_OUT &&
                it.type == UsbConstants.USB_ENDPOINT_XFER_BULK &&
                (wantedOut == null || it.address == wantedOut)
        }
        return if (input != null && output != null) input to output else null
    }

    private const val CDC_FUNCTIONAL_DESCRIPTOR_TYPE = 0x24
    private const val CDC_UNION_SUBTYPE = 0x06
}
