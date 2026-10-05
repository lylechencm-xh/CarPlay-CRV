package com.shilapi.xcertplay.transport

import android.annotation.TargetApi
import android.hardware.usb.UsbConfiguration
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbInterface
import android.util.Log

/** API21-only multi-configuration discovery, isolated so API17 never loads UsbConfiguration. */
@TargetApi(21)
internal object IphoneCarPlayConfigurationApi21 {
    private const val USBMUX_CLASS = 0xff
    private const val USBMUX_SUBCLASS = 0xfe
    private const val USBMUX_PROTOCOL = 0x02
    private const val APPLE_ETHERNET_CLASS = 0xff
    private const val APPLE_ETHERNET_SUBCLASS = 0xfd
    private const val APPLE_ETHERNET_PROTOCOL = 0x01
    private const val NCM_CONTROL_CLASS = 0x02
    private const val NCM_CONTROL_SUBCLASS = 0x0d

    fun find(device: UsbDevice): UsbConfiguration? {
        val configurations = (0 until device.configurationCount).map(device::getConfiguration)
        val chosen = configurations.firstOrNull {
            usbMuxInterface(it) != null && hasCdcNcm(it) && hasAppleEthernet(it)
        } ?: configurations.firstOrNull {
            usbMuxInterface(it) != null && hasCdcNcm(it)
        }
        Log.i(
            IphoneCarPlayConfiguration.TAG,
            "carplay config chosen=${chosen?.id} available=${configurations.map { it.id }}",
        )
        return chosen
    }

    fun usbMuxInterface(configuration: UsbConfiguration): UsbInterface? =
        (0 until configuration.interfaceCount).map(configuration::getInterface).firstOrNull {
            it.interfaceClass == USBMUX_CLASS &&
                it.interfaceSubclass == USBMUX_SUBCLASS &&
                it.interfaceProtocol == USBMUX_PROTOCOL
        }

    private fun hasCdcNcm(configuration: UsbConfiguration): Boolean =
        (0 until configuration.interfaceCount).map(configuration::getInterface).any {
            it.interfaceClass == NCM_CONTROL_CLASS && it.interfaceSubclass == NCM_CONTROL_SUBCLASS
        }

    private fun hasAppleEthernet(configuration: UsbConfiguration): Boolean =
        (0 until configuration.interfaceCount).map(configuration::getInterface).any {
            it.interfaceClass == APPLE_ETHERNET_CLASS &&
                it.interfaceSubclass == APPLE_ETHERNET_SUBCLASS &&
                it.interfaceProtocol == APPLE_ETHERNET_PROTOCOL
        }
}
