package com.shilapi.xcertplay.transport

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UsbActiveConfigurationTest {
    @Test
    fun scopesDuplicateInterfaceNumbersToRequestedConfiguration() {
        val raw = concat(
            configuration(1, 2),
            interfaceDescriptor(2, 0, 1, 0x02, 0x0d, 0),
            byteArrayOf(5, 0x24, 0x06, 2, 3),
            endpoint(0x81, 0x03),
            interfaceDescriptor(3, 0, 0, 0x0a, 0, 0),
            interfaceDescriptor(3, 1, 2, 0x0a, 0, 0),
            endpoint(0x87, 0x02),
            endpoint(0x05, 0x02),
            configuration(6, 2),
            interfaceDescriptor(5, 0, 1, 0x02, 0x0d, 0),
            byteArrayOf(5, 0x24, 0x06, 5, 6),
            endpoint(0x82, 0x03),
            interfaceDescriptor(6, 0, 0, 0x0a, 0, 0),
            interfaceDescriptor(6, 1, 2, 0x0a, 0, 0),
            endpoint(0x88, 0x02),
            endpoint(0x06, 0x02),
        )

        val selected = UsbActiveConfiguration.interfaces(raw, 6)

        assertEquals(listOf(5, 6, 6), selected.map { it.number })
        assertEquals(listOf(0, 0, 1), selected.map { it.alternateSetting })
        assertTrue(selected.none { it.number == 2 || it.number == 3 })
        assertEquals(listOf(0x88, 0x06), selected.last().endpoints.map { it.address })
        assertEquals(0x24, selected.first().extraDescriptors.single()[1].toInt() and 0xff)
    }

    @Test
    fun malformedDescriptorStopsWithoutLeakingAnotherConfiguration() {
        val raw = concat(
            configuration(6, 1),
            interfaceDescriptor(5, 0, 0, 0x02, 0x0d, 0),
            byteArrayOf(0, 0x04),
            configuration(1, 1),
            interfaceDescriptor(2, 0, 0, 0x02, 0x0d, 0),
        )

        val selected = UsbActiveConfiguration.interfaces(raw, 6)
        assertEquals(listOf(5), selected.map { it.number })
    }

    private fun configuration(value: Int, interfaces: Int): ByteArray =
        byteArrayOf(9, 2, 0, 0, interfaces.toByte(), value.toByte(), 0, 0x80.toByte(), 50)

    private fun interfaceDescriptor(
        number: Int,
        alt: Int,
        endpoints: Int,
        klass: Int,
        subclass: Int,
        protocol: Int,
    ): ByteArray = byteArrayOf(
        9, 4, number.toByte(), alt.toByte(), endpoints.toByte(),
        klass.toByte(), subclass.toByte(), protocol.toByte(), 0,
    )

    private fun endpoint(address: Int, attributes: Int): ByteArray =
        byteArrayOf(7, 5, address.toByte(), attributes.toByte(), 0x00, 0x02, 0)

    private fun concat(vararg blocks: ByteArray): ByteArray {
        val size = blocks.sumOf { it.size }
        val result = ByteArray(size)
        var offset = 0
        for (block in blocks) {
            block.copyInto(result, offset)
            offset += block.size
        }
        return result
    }
}
