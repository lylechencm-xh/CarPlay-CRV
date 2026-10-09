package com.shilapi.xcertplay

import org.junit.Assert.assertEquals
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

    /**
     * The CR-V head unit binds a matching cdc_ncm netdev, but NetworkUtils.enableInterface() returns
     * -1. The fallback must report that the kernel driver was found but the network never came up,
     * separately from a device that never had a matching kernel driver at all.
     */
    @Test
    fun fallbackReasonSeparatesBoundButNotReadyDriverFromMissingDriver() {
        assertEquals(
            CrvUsbKernelProbe.KernelFallbackReason.DRIVER_BOUND_NETWORK_NOT_READY,
            CrvUsbKernelProbe.KernelBringUpResult(
                interfaceName = "usb2",
                attempted = true,
                resultCode = -1,
                error = null,
            ).fallbackReason,
        )
        assertEquals(
            CrvUsbKernelProbe.KernelFallbackReason.DRIVER_BOUND_NETWORK_NOT_READY,
            CrvUsbKernelProbe.KernelBringUpResult(
                interfaceName = "usb2",
                attempted = true,
                resultCode = null,
                error = "SecurityException: EPERM",
            ).fallbackReason,
        )
        assertEquals(
            CrvUsbKernelProbe.KernelFallbackReason.MATCHING_DRIVER_NOT_FOUND,
            CrvUsbKernelProbe.KernelBringUpResult(
                interfaceName = null,
                attempted = false,
                resultCode = null,
                error = "no matching cdc_ncm netdev",
            ).fallbackReason,
        )
    }

    /** The fallback reason text is part of the field diagnostics contract. */
    @Test
    fun fallbackReasonDiagnosticsMatchTheReportedLogText() {
        assertEquals(
            "kernel-driver-bound-netdev-not-ready",
            CrvUsbKernelProbe.KernelFallbackReason.DRIVER_BOUND_NETWORK_NOT_READY.diagnostic,
        )
        assertEquals(
            "matching-kernel-driver-not-found",
            CrvUsbKernelProbe.KernelFallbackReason.MATCHING_DRIVER_NOT_FOUND.diagnostic,
        )
    }
}
