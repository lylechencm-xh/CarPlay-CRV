package com.shilapi.xcertplay

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CrvUsbKernelProbeTest {
    @Test
    fun bringUpSuccessRequiresMatchingInterfaceNoErrorAndZeroResult() {
        assertTrue(
            CrvUsbKernelProbe.KernelBringUpResult(
                interfaceName = "usb2",
                attempted = true,
                resultCode = 0,
                error = null,
            ).successful,
        )
        assertTrue(
            CrvUsbKernelProbe.KernelBringUpResult(
                interfaceName = "usb2",
                attempted = false,
                resultCode = 0,
                error = null,
            ).successful,
        )

        assertFalse(
            CrvUsbKernelProbe.KernelBringUpResult(
                interfaceName = "usb2",
                attempted = true,
                resultCode = null,
                error = "SecurityException: EPERM",
            ).successful,
        )
        assertFalse(
            CrvUsbKernelProbe.KernelBringUpResult(
                interfaceName = "usb2",
                attempted = true,
                resultCode = -1,
                error = null,
            ).successful,
        )
        assertFalse(
            CrvUsbKernelProbe.KernelBringUpResult(
                interfaceName = null,
                attempted = false,
                resultCode = 0,
                error = null,
            ).successful,
        )
    }
}
