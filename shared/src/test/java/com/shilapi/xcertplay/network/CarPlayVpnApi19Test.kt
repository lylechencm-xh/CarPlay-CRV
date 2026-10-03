package com.shilapi.xcertplay.network

import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.net.VpnService
import android.os.Build
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
@Config(sdk = [21], manifest = Config.NONE, shadows = [VpnApi19BuilderShadow::class])
class CarPlayVpnApi19Test {
    @Test fun api19SkipsModernVpnScopingAndReleasesFailedAttachment() {
        VpnApi19Boundary.calls.clear()
        val originalSdk = Build.VERSION.SDK_INT
        val controller = Robolectric.buildService(CarPlayVpnService::class.java).create()
        try {
            val service = controller.get()
            ReflectionHelpers.setStaticField(Build.VERSION::class.java, "SDK_INT", 19)
            val result = service.attach(
                ncm(),
                "fe80::1234",
                ByteArray(6),
                config,
                identity,
                PairingStore(),
                null,
                object : AirPlaySessionListener {},
                object : AirPlayMediaHandler {},
            )

            assertEquals(
                CarPlayVpnService.AttachResult.Failed("VpnService.establish returned null"),
                result,
            )
            assertEquals(listOf("establish"), VpnApi19Boundary.calls)
            assertFalse(service.isAttached())
            assertFalse(ReflectionHelpers.getField<AtomicBoolean>(service, "active").get())
            assertNull(service.boundPort())
            assertNull(ReflectionHelpers.getField<Any?>(service, "attachment"))
            assertNull(ReflectionHelpers.getField<Any?>(service, "bridge"))
            assertNull(ReflectionHelpers.getField<Any?>(service, "tun"))
        } finally {
            ReflectionHelpers.setStaticField(Build.VERSION::class.java, "SDK_INT", originalSdk)
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

    private val config = AirPlayConfig(
        "test",
        "00:00:00:00:00:01",
        "00:00:00:00:00:02",
        "1",
        AirPlayDisplayConfig(800, 480),
        port = 0,
    )
    private val identity = AirPlayIdentity(ByteArray(32), ByteArray(32), "test")
}

object VpnApi19Boundary {
    val calls = mutableListOf<String>()
}

@Implements(VpnService.Builder::class)
class VpnApi19BuilderShadow {
    @RealObject private lateinit var builder: VpnService.Builder

    @Implementation fun addAddress(address: String, prefixLength: Int): VpnService.Builder = builder

    @Implementation fun addRoute(address: String, prefixLength: Int): VpnService.Builder = builder

    @Implementation fun establish(): ParcelFileDescriptor? {
        VpnApi19Boundary.calls += "establish"
        return null
    }
}
