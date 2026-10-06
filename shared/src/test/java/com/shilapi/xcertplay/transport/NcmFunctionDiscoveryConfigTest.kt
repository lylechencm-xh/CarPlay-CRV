package com.shilapi.xcertplay.transport

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class NcmFunctionDiscoveryConfigTest {
    @Test
    fun followsCdcUnionInsideActiveConfiguration() {
        val raw = concat(
            configuration(1, 2),
            interfaceDescriptor(2, 0, 1, 0x02, 0x0d, 0),
            byteArrayOf(5, 0x24, 0x06, 2, 3),
            endpoint(0x81, 0x03),
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

        val pair = NcmFunctionDiscovery.descriptorPair(raw, 6)
        assertNotNull(pair)
        assertEquals(5, pair!!.first.number)
        assertEquals(6, pair.second.number)
        assertEquals(1, pair.second.alternateSetting)
        assertEquals(listOf(0x88, 0x06), pair.second.endpoints.map { it.address })
    }

    @Test
    fun failsClosedWhenUnionIsMissingAndMultipleDataInterfacesExist() {
        val raw = concat(
            configuration(6, 3),
            interfaceDescriptor(5, 0, 1, 0x02, 0x0d, 0),
            endpoint(0x82, 0x03),
            interfaceDescriptor(6, 1, 2, 0x0a, 0, 0),
            endpoint(0x88, 0x02),
            endpoint(0x06, 0x02),
            interfaceDescriptor(7, 1, 2, 0x0a, 0, 0),
            endpoint(0x89, 0x02),
            endpoint(0x07, 0x02),
        )

        assertNull(NcmFunctionDiscovery.descriptorPair(raw, 6))
    }

    @Test
    fun allowsUniqueDataFallbackWhenUnionIsMissing() {
        val raw = concat(
            configuration(6, 2),
            interfaceDescriptor(5, 0, 1, 0x02, 0x0d, 0),
            endpoint(0x82, 0x03),
            interfaceDescriptor(6, 1, 2, 0x0a, 0, 0),
            endpoint(0x88, 0x02),
            endpoint(0x06, 0x02),
        )

        val pair = NcmFunctionDiscovery.descriptorPair(raw, 6)
        assertNotNull(pair)
        assertEquals(5, pair!!.first.number)
        assertEquals(6, pair.second.number)
    }

    @Test
    fun ethernetMacDescriptorIsScopedToActiveConfiguration() {
        val raw = concat(
            configuration(1, 1),
            interfaceDescriptor(5, 0, 0, 0x02, 0x0d, 0),
            byteArrayOf(13, 0x24, 0x0f, 4, 0, 0, 0, 0, 0, 0, 0, 0, 0),
            configuration(6, 1),
            interfaceDescriptor(5, 0, 0, 0x02, 0x0d, 0),
            byteArrayOf(13, 0x24, 0x0f, 9, 0, 0, 0, 0, 0, 0, 0, 0, 0),
        )

        assertEquals(9, NcmUsbBridge.ethernetMacStringIndex(raw, 6, 5))
        assertEquals(4, NcmUsbBridge.ethernetMacStringIndex(raw, 1, 5))
    }

    @Test
    fun doesNotPairDataFromAnotherConfiguration() {
        val raw = concat(
            configuration(6, 1),
            interfaceDescriptor(5, 0, 1, 0x02, 0x0d, 0),
            byteArrayOf(5, 0x24, 0x06, 5, 6),
            endpoint(0x82, 0x03),
            configuration(1, 1),
            interfaceDescriptor(6, 1, 2, 0x0a, 0, 0),
            endpoint(0x88, 0x02),
            endpoint(0x06, 0x02),
        )

        assertEquals(null, NcmFunctionDiscovery.descriptorPair(raw, 6))
    }

    private fun configuration(value: Int, interfaces: Int): ByteArray =
        byteArrayOf(9, 2, 0, 0, interfaces.toByte(), value.toByte(), 0, 0x80.toByte(), 50)

    private fun interfaceDescriptor(
        number: Int, alt: Int, endpoints: Int, klass: Int, subclass: Int, protocol: Int,
    ): ByteArray = byteArrayOf(
        9, 4, number.toByte(), alt.toByte(), endpoints.toByte(),
        klass.toByte(), subclass.toByte(), protocol.toByte(), 0,
    )

    private fun endpoint(address: Int, attributes: Int): ByteArray =
        byteArrayOf(7, 5, address.toByte(), attributes.toByte(), 0x00, 0x02, 0)

    private fun concat(vararg blocks: ByteArray): ByteArray {
        val result = ByteArray(blocks.sumOf { it.size })
        var offset = 0
        for (block in blocks) {
            block.copyInto(result, offset)
            offset += block.size
        }
        return result
    }
}
