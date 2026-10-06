package com.shilapi.xcertplay.transport

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbInterface

/**
 * API17-safe view of the currently active USB configuration.
 *
 * Android 4.2.x flattens interfaces from UsbDevice and does not expose UsbConfiguration. Reading
 * GET_CONFIGURATION and correlating it with raw descriptors prevents mixing interfaces that share
 * the same bInterfaceNumber across different configurations.
 */
object UsbActiveConfiguration {
    data class EndpointDescriptor(
        val address: Int,
        val attributes: Int,
        val maxPacketSize: Int,
    ) {
        val type: Int get() = attributes and 0x03
        val direction: Int get() = address and UsbConstants.USB_DIR_IN
    }

    data class InterfaceDescriptor(
        val configurationValue: Int,
        val number: Int,
        val alternateSetting: Int,
        val endpointCount: Int,
        val interfaceClass: Int,
        val interfaceSubclass: Int,
        val interfaceProtocol: Int,
        val endpoints: List<EndpointDescriptor>,
        val extraDescriptors: List<ByteArray>,
    )

    fun readValue(connection: UsbDeviceConnection): Int? {
        val result = ByteArray(1)
        val transferred = try {
            connection.controlTransfer(
                UsbConstants.USB_DIR_IN or UsbConstants.USB_TYPE_STANDARD,
                USB_REQUEST_GET_CONFIGURATION,
                0,
                0,
                result,
                result.size,
                CONTROL_TIMEOUT_MILLIS,
            )
        } catch (_: RuntimeException) {
            return null
        }
        if (transferred != 1) return null
        return (result[0].toInt() and 0xff).takeIf { it != 0 }
    }

    fun configurationValues(raw: ByteArray): List<Int> {
        val values = ArrayList<Int>()
        var offset = 0
        while (offset + 2 <= raw.size) {
            val length = raw[offset].toInt() and 0xff
            val type = raw[offset + 1].toInt() and 0xff
            if (length < 2 || offset + length > raw.size) break
            if (type == USB_CONFIGURATION_DESCRIPTOR_TYPE &&
                length >= USB_CONFIGURATION_DESCRIPTOR_LENGTH
            ) {
                val value = raw[offset + 5].toInt() and 0xff
                if (value != 0 && value !in values) values += value
            }
            offset += length
        }
        return values
    }

    fun selectValue(connection: UsbDeviceConnection, configurationValue: Int): Boolean {
        require(configurationValue in 1..255) { "USB configuration value must fit in one byte" }
        val transferred = try {
            connection.controlTransfer(
                UsbConstants.USB_DIR_OUT or UsbConstants.USB_TYPE_STANDARD,
                USB_REQUEST_SET_CONFIGURATION,
                configurationValue,
                0,
                null,
                0,
                CONTROL_TIMEOUT_MILLIS,
            )
        } catch (_: RuntimeException) {
            return false
        }
        if (transferred != 0) return false
        repeat(CONFIGURATION_VERIFY_ATTEMPTS) {
            if (readValue(connection) == configurationValue) return true
            try {
                Thread.sleep(CONFIGURATION_VERIFY_DELAY_MILLIS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return false
            }
        }
        return false
    }

    fun interfaces(raw: ByteArray, configurationValue: Int): List<InterfaceDescriptor> {
        val result = ArrayList<InterfaceDescriptor>()
        var currentConfiguration = -1
        var currentConfigurationEnd = Int.MAX_VALUE
        var current: MutableInterface? = null
        var offset = 0

        fun flush() {
            val value = current ?: return
            if (value.configurationValue == configurationValue) {
                result += value.freeze()
            }
            current = null
        }

        while (offset + 2 <= raw.size) {
            if (offset >= currentConfigurationEnd) {
                flush()
                currentConfiguration = -1
                currentConfigurationEnd = Int.MAX_VALUE
            }

            val length = raw[offset].toInt() and 0xff
            val type = raw[offset + 1].toInt() and 0xff
            if (length < 2 || offset + length > raw.size) break
            if (currentConfiguration != -1 && offset + length > currentConfigurationEnd) {
                flush()
                offset = currentConfigurationEnd
                currentConfiguration = -1
                currentConfigurationEnd = Int.MAX_VALUE
                continue
            }

            when (type) {
                USB_CONFIGURATION_DESCRIPTOR_TYPE -> {
                    flush()
                    currentConfiguration =
                        if (length >= USB_CONFIGURATION_DESCRIPTOR_LENGTH) {
                            raw[offset + 5].toInt() and 0xff
                        } else {
                            -1
                        }
                    val totalLength =
                        if (length >= USB_CONFIGURATION_DESCRIPTOR_LENGTH) {
                            (raw[offset + 2].toInt() and 0xff) or
                                ((raw[offset + 3].toInt() and 0xff) shl 8)
                        } else {
                            0
                        }
                    currentConfigurationEnd =
                        if (totalLength >= USB_CONFIGURATION_DESCRIPTOR_LENGTH) {
                            (offset + totalLength).coerceAtMost(raw.size)
                        } else {
                            Int.MAX_VALUE
                        }
                }

                USB_INTERFACE_DESCRIPTOR_TYPE -> {
                    flush()
                    if (length >= USB_INTERFACE_DESCRIPTOR_LENGTH) {
                        current = MutableInterface(
                            configurationValue = currentConfiguration,
                            number = raw[offset + 2].toInt() and 0xff,
                            alternateSetting = raw[offset + 3].toInt() and 0xff,
                            endpointCount = raw[offset + 4].toInt() and 0xff,
                            interfaceClass = raw[offset + 5].toInt() and 0xff,
                            interfaceSubclass = raw[offset + 6].toInt() and 0xff,
                            interfaceProtocol = raw[offset + 7].toInt() and 0xff,
                        )
                    }
                }

                USB_ENDPOINT_DESCRIPTOR_TYPE -> {
                    val target = current
                    if (target != null && length >= USB_ENDPOINT_DESCRIPTOR_LENGTH) {
                        target.endpoints += EndpointDescriptor(
                            address = raw[offset + 2].toInt() and 0xff,
                            attributes = raw[offset + 3].toInt() and 0xff,
                            maxPacketSize =
                                (raw[offset + 4].toInt() and 0xff) or
                                    ((raw[offset + 5].toInt() and 0xff) shl 8),
                        )
                    }
                }

                else -> {
                    current?.extraDescriptors?.add(raw.copyOfRange(offset, offset + length))
                }
            }
            offset += length
        }
        flush()
        return result
    }

    fun androidInterface(
        device: UsbDevice,
        descriptor: InterfaceDescriptor,
    ): UsbInterface? {
        val candidates = (0 until device.interfaceCount)
            .map(device::getInterface)
            .filter {
                it.id == descriptor.number &&
                    it.interfaceClass == descriptor.interfaceClass &&
                    it.interfaceSubclass == descriptor.interfaceSubclass &&
                    it.interfaceProtocol == descriptor.interfaceProtocol &&
                    it.endpointCount == descriptor.endpointCount
            }
        if (candidates.size <= 1) return candidates.firstOrNull()

        val wantedAddresses = descriptor.endpoints.map { it.address }.sorted()
        return candidates.firstOrNull { usbInterface ->
            (0 until usbInterface.endpointCount)
                .map { usbInterface.getEndpoint(it).address }
                .sorted() == wantedAddresses
        } ?: candidates.firstOrNull()
    }

    private data class MutableInterface(
        val configurationValue: Int,
        val number: Int,
        val alternateSetting: Int,
        val endpointCount: Int,
        val interfaceClass: Int,
        val interfaceSubclass: Int,
        val interfaceProtocol: Int,
        val endpoints: MutableList<EndpointDescriptor> = ArrayList(),
        val extraDescriptors: MutableList<ByteArray> = ArrayList(),
    ) {
        fun freeze(): InterfaceDescriptor = InterfaceDescriptor(
            configurationValue = configurationValue,
            number = number,
            alternateSetting = alternateSetting,
            endpointCount = endpointCount,
            interfaceClass = interfaceClass,
            interfaceSubclass = interfaceSubclass,
            interfaceProtocol = interfaceProtocol,
            endpoints = endpoints.toList(),
            extraDescriptors = extraDescriptors.map(ByteArray::copyOf),
        )
    }

    private const val USB_REQUEST_GET_CONFIGURATION = 0x08
    private const val USB_REQUEST_SET_CONFIGURATION = 0x09
    private const val USB_CONFIGURATION_DESCRIPTOR_TYPE = 0x02
    private const val USB_INTERFACE_DESCRIPTOR_TYPE = 0x04
    private const val USB_ENDPOINT_DESCRIPTOR_TYPE = 0x05
    private const val USB_CONFIGURATION_DESCRIPTOR_LENGTH = 9
    private const val USB_INTERFACE_DESCRIPTOR_LENGTH = 9
    private const val USB_ENDPOINT_DESCRIPTOR_LENGTH = 7
    private const val CONTROL_TIMEOUT_MILLIS = 1_000
    private const val CONFIGURATION_VERIFY_ATTEMPTS = 10
    private const val CONFIGURATION_VERIFY_DELAY_MILLIS = 25L
}
