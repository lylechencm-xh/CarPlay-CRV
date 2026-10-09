package com.shilapi.xcertplay.transport

import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.util.ReflectionHelpers
import org.robolectric.util.ReflectionHelpers.ClassParameter

/**
 * The userspace NCM fallback must never silently detach a kernel driver, and its two failure modes -
 * a busy interface and a rejected alternate setting - must surface as classified errors instead of
 * an opaque transport failure.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE, shadows = [NcmClaimBoundaryShadow::class])
class NcmClaimPolicyTest {
    @Before fun resetBoundary() { NcmClaimBoundary.reset() }

    @Test fun preservingTheKernelDriverClaimsWithoutForcing() {
        val bridge = open(NcmClaimPolicy.PRESERVE_KERNEL_DRIVER)
        try {
            assertEquals(listOf(NcmClaimBoundary.Claim(0, force = false)), NcmClaimBoundary.claims)
            assertEquals(1, NcmClaimBoundary.setInterfaceCalls)
            assertFalse(NcmClaimBoundary.connectionClosed)
        } finally {
            bridge.close()
        }
        assertEquals(listOf(0), NcmClaimBoundary.released)
        assertTrue(NcmClaimBoundary.connectionClosed)
    }

    @Test fun busyInterfaceUnderThePreservingPolicyIsClassifiedWithoutDetachingTheDriver() {
        NcmClaimBoundary.claimSucceeds = false

        val error = runCatching { open(NcmClaimPolicy.PRESERVE_KERNEL_DRIVER) }.exceptionOrNull()

        assertTrue("expected InterfaceBusy but was $error", error is IphoneUsbException.InterfaceBusy)
        val message = error!!.message.orEmpty()
        assertTrue(message, message.contains("kernelDriverDetachAllowed=false"))
        assertEquals(listOf(NcmClaimBoundary.Claim(0, force = false)), NcmClaimBoundary.claims)
        assertEquals(emptyList<Int>(), NcmClaimBoundary.released)
        assertTrue(NcmClaimBoundary.connectionClosed)
    }

    @Test fun detachingPolicyRecordsThatItForcedTheClaim() {
        NcmClaimBoundary.claimSucceeds = false

        val error = runCatching { open(NcmClaimPolicy.DETACH_KERNEL_DRIVER) }.exceptionOrNull()

        assertTrue("expected InterfaceBusy but was $error", error is IphoneUsbException.InterfaceBusy)
        assertTrue(error!!.message.orEmpty().contains("kernelDriverDetachAllowed=true"))
        assertEquals(listOf(NcmClaimBoundary.Claim(0, force = true)), NcmClaimBoundary.claims)
    }

    @Test fun rejectedAlternateSettingReleasesTheClaimedInterface() {
        NcmClaimBoundary.setInterfaceSucceeds = false

        val error = runCatching { open(NcmClaimPolicy.PRESERVE_KERNEL_DRIVER) }.exceptionOrNull()

        assertTrue(
            "expected DeviceUnavailable but was $error",
            error is IphoneUsbException.DeviceUnavailable,
        )
        assertTrue(error!!.message.orEmpty().contains("NCM data alternate setting"))
        assertEquals(listOf(0), NcmClaimBoundary.released)
        assertTrue(NcmClaimBoundary.connectionClosed)
    }

    /** Claim policy is explicit: neither constant may fall back to the historical forced claim. */
    @Test fun bothPoliciesCarryAnExplicitForceFlag() {
        assertFalse(NcmClaimPolicy.PRESERVE_KERNEL_DRIVER.force)
        assertTrue(NcmClaimPolicy.DETACH_KERNEL_DRIVER.force)
    }

    private fun open(policy: NcmClaimPolicy): NcmUsbBridge {
        val connection = ReflectionHelpers.callConstructor(
            UsbDeviceConnection::class.java,
            ClassParameter.from(UsbDevice::class.java, null),
        )
        return NcmUsbBridge.open(connection, function(), policy)
    }

    private fun function(): NcmFunctionDiscovery.NcmFunction {
        // Apple exposes the NCM control and data functions as alternate settings of one interface.
        val usbInterface = ReflectionHelpers.callConstructor(
            UsbInterface::class.java,
            ClassParameter.from(Int::class.javaPrimitiveType, 0),
            ClassParameter.from(Int::class.javaPrimitiveType, 1),
            ClassParameter.from(String::class.java, "ncm"),
            ClassParameter.from(Int::class.javaPrimitiveType, NcmFunctionDiscovery.CONTROL_CLASS),
            ClassParameter.from(Int::class.javaPrimitiveType, NcmFunctionDiscovery.CONTROL_SUBCLASS),
            ClassParameter.from(Int::class.javaPrimitiveType, 0),
        )
        return NcmFunctionDiscovery.NcmFunction(
            configurationValue = null,
            control = usbInterface,
            data = usbInterface,
            dataAlternateSetting = 1,
            statusIn = null,
            bulkIn = endpoint(0x85),
            bulkOut = endpoint(0x04),
        )
    }

    private fun endpoint(address: Int): UsbEndpoint = ReflectionHelpers.callConstructor(
        UsbEndpoint::class.java,
        ClassParameter.from(Int::class.javaPrimitiveType, address),
        ClassParameter.from(Int::class.javaPrimitiveType, 2),
        ClassParameter.from(Int::class.javaPrimitiveType, 512),
        ClassParameter.from(Int::class.javaPrimitiveType, 0),
    )
}

/** Records the exact USB boundary calls the NCM fallback makes, and controls their outcome. */
object NcmClaimBoundary {
    data class Claim(val interfaceId: Int, val force: Boolean)

    val claims = mutableListOf<Claim>()
    val released = mutableListOf<Int>()
    var claimSucceeds = true
    var setInterfaceSucceeds = true
    var setInterfaceCalls = 0
    var connectionClosed = false

    fun reset() {
        claims.clear()
        released.clear()
        claimSucceeds = true
        setInterfaceSucceeds = true
        setInterfaceCalls = 0
        connectionClosed = false
    }
}

@Implements(UsbDeviceConnection::class)
class NcmClaimBoundaryShadow {
    @Implementation fun claimInterface(intf: UsbInterface, force: Boolean): Boolean {
        NcmClaimBoundary.claims += NcmClaimBoundary.Claim(intf.id, force)
        return NcmClaimBoundary.claimSucceeds
    }

    @Implementation fun releaseInterface(intf: UsbInterface): Boolean {
        NcmClaimBoundary.released += intf.id
        return true
    }

    @Implementation fun setInterface(intf: UsbInterface): Boolean {
        NcmClaimBoundary.setInterfaceCalls += 1
        return NcmClaimBoundary.setInterfaceSucceeds
    }

    @Implementation fun close() {
        NcmClaimBoundary.connectionClosed = true
    }
}
