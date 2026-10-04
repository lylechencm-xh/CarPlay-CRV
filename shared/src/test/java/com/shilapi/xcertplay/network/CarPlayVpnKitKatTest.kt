package com.shilapi.xcertplay.network

import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.net.VpnService
import android.os.ParcelFileDescriptor
import com.shilapi.xcertplay.airplay.AirPlayConfig
import com.shilapi.xcertplay.airplay.AirPlayDisplayConfig
import com.shilapi.xcertplay.airplay.AirPlayIdentity
import com.shilapi.xcertplay.airplay.AirPlayMediaHandler
import com.shilapi.xcertplay.airplay.AirPlaySessionListener
import com.shilapi.xcertplay.airplay.PairingStore
import com.shilapi.xcertplay.transport.NcmUsbBridge
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.annotation.RealObject
import org.robolectric.util.ReflectionHelpers
import org.robolectric.util.ReflectionHelpers.ClassParameter

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [19], manifest = Config.NONE, shadows = [KitKatVpnBuilderShadow::class])
class CarPlayVpnKitKatTest {
    @Test
    fun wiredAttachUsesLegacyBuilderWithoutModernScopingCalls() {
        KitKatVpnBoundary.calls.clear()
        val controller = Robolectric.buildService(CarPlayVpnService::class.java).create()
        val service = controller.get()
        try {
            val result = service.attach(
                ncm = ncm(),
                linkLocal = "fe80::1234",
                hostMac = ByteArray(6),
                config = AirPlayConfig(
                    deviceName = "test",
                    deviceId = "00:00:00:00:00:01",
                    btMac = "00:00:00:00:00:02",
                    sourceVersion = "1",
                    main = AirPlayDisplayConfig(800, 480),
                    port = 0,
                ),
                identity = AirPlayIdentity(ByteArray(32), ByteArray(32), "test"),
                pairings = PairingStore(),
                mfi = null,
                listener = object : AirPlaySessionListener {},
                media = object : AirPlayMediaHandler {},
            )

            assertEquals(
                CarPlayVpnService.AttachResult.Failed("VpnService.establish returned null"),
                result,
            )
            assertEquals(listOf("establish"), KitKatVpnBoundary.calls)
            assertFalse(service.isAttached())
            assertFalse(ReflectionHelpers.getField<AtomicBoolean>(service, "active").get())
            assertNull(service.boundPort())
        } finally {
            controller.destroy()
        }
    }

    private fun ncm(): NcmUsbBridge {
        val connection = ReflectionHelpers.callConstructor(
            UsbDeviceConnection::class.java,
            ClassParameter.from(UsbDevice::class.java, null),
        )
        fun endpoint(address: Int): UsbEndpoint = ReflectionHelpers.callConstructor(
            UsbEndpoint::class.java,
            ClassParameter.from(Int::class.javaPrimitiveType, address),
            ClassParameter.from(Int::class.javaPrimitiveType, 2),
            ClassParameter.from(Int::class.javaPrimitiveType, 512),
            ClassParameter.from(Int::class.javaPrimitiveType, 0),
        )
        return NcmUsbBridge(connection, endpoint(0x04), endpoint(0x85), null, emptyList(), null)
    }
}

object KitKatVpnBoundary {
    val calls = mutableListOf<String>()
}

@Implements(VpnService.Builder::class)
class KitKatVpnBuilderShadow {
    @RealObject private lateinit var builder: VpnService.Builder

    @Implementation fun addAddress(address: String, prefixLength: Int): VpnService.Builder = builder
    @Implementation fun addRoute(address: String, prefixLength: Int): VpnService.Builder = builder

    @Implementation fun establish(): ParcelFileDescriptor? {
        KitKatVpnBoundary.calls += "establish"
        return null
    }

    // Deliberately present only as a tripwire. The API19 branch must never call these.
    @Implementation fun addAllowedApplication(packageName: String): VpnService.Builder {
        KitKatVpnBoundary.calls += "allow:$packageName"
        error("API19 must not call addAllowedApplication")
    }

    @Implementation fun setBlocking(blocking: Boolean): VpnService.Builder {
        KitKatVpnBoundary.calls += "blocking:$blocking"
        error("API19 must not call setBlocking")
    }
}
