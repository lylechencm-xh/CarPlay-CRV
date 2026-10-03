package com.shilapi.xcertplay

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.ActivityInfo
import android.graphics.Color
import android.graphics.SurfaceTexture
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Bundle
import android.os.IBinder
import android.view.Gravity
import android.view.MotionEvent
import android.view.Surface
import android.view.TextureView
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import com.shilapi.xcertplay.network.CarPlayVpnService
import com.shilapi.xcertplay.transport.Iap2UsbSession
import com.shilapi.xcertplay.transport.IphoneUsbHost
import com.shilapi.xcertplay.transport.IphoneUsbMatcher
import java.io.Closeable
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/** Android 4.4 wired CarPlay entry point for the 2021 Honda CR-V. */
class CrvCarPlayActivity : Activity(), TextureView.SurfaceTextureListener {
    private lateinit var usbManager: UsbManager
    private lateinit var usbHost: IphoneUsbHost
    private lateinit var video: TextureView
    private lateinit var status: TextView

    private val io: ExecutorService = Executors.newSingleThreadExecutor()
    private var permissionReceiver: Closeable? = null
    private var attachReceiver: Closeable? = null

    @Volatile private var awaitingCarPlayReattach = false
    private var pendingUsbSession: Iap2UsbSession? = null
    private var pendingUsbDevice: UsbDevice? = null

    private var renderSurface: Surface? = null
    private var renderWidth = 0
    private var renderHeight = 0

    private var vpnService: CarPlayVpnService? = null
    private var vpnBound = false
    private var controller: CrvWiredCarPlayController? = null

    private val vpnConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            vpnService = (binder as CarPlayVpnService.LocalBinder).service
            vpnBound = true
            setStatus("CarPlay network ready")
            maybeStartController()
        }

        override fun onServiceDisconnected(name: ComponentName) {
            vpnService = null
            vpnBound = false
            controller?.close()
            controller = null
            setStatus("CarPlay network service stopped")
        }
    }

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        video = TextureView(this).apply {
            surfaceTextureListener = this@CrvCarPlayActivity
            isOpaque = true
            setOnTouchListener { view, event ->
                val active = controller ?: return@setOnTouchListener false
                if (view.width <= 0 || view.height <= 0) return@setOnTouchListener false
                val down = event.actionMasked != MotionEvent.ACTION_UP &&
                    event.actionMasked != MotionEvent.ACTION_CANCEL
                active.sendTouch(
                    event.x.toDouble() / view.width.toDouble(),
                    event.y.toDouble() / view.height.toDouble(),
                    down,
                )
            }
        }

        status = TextView(this).apply {
            text = "Preparing wired CarPlay"
            setTextColor(Color.WHITE)
            setBackgroundColor(0x66000000)
            gravity = Gravity.CENTER
            setPadding(16, 10, 16, 10)
        }

        setContentView(FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            addView(video, FrameLayout.LayoutParams(-1, -1))
            addView(status, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
        })

        prepareVpn()
        prepareUsb()
    }

    private fun prepareVpn() {
        val consent = CarPlayVpnService.prepare(this)
        if (consent == null) {
            bindVpnService()
        } else {
            setStatus("Allow CarPlay network access")
            startActivityForResult(consent, REQUEST_VPN)
        }
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_VPN) return
        if (resultCode == RESULT_OK) {
            bindVpnService()
        } else {
            setStatus("VPN permission is required for wired CarPlay")
        }
    }

    private fun bindVpnService() {
        if (vpnBound) return
        val intent = Intent(this, CarPlayVpnService::class.java)
        if (!bindService(intent, vpnConnection, Context.BIND_AUTO_CREATE)) {
            setStatus("Could not start CarPlay network service")
        }
    }

    private fun prepareUsb() {
        usbManager = getSystemService(USB_SERVICE) as UsbManager
        usbHost = IphoneUsbHost(this, usbManager, IphoneUsbMatcher.appleVendor())

        permissionReceiver = usbHost.registerPermissionReceiver { result ->
            when (result) {
                is IphoneUsbHost.PermissionResult.Granted ->
                    if (awaitingCarPlayReattach) openCarPlayUsb(result.device) else beginUsb(result.device)
                is IphoneUsbHost.PermissionResult.Denied -> setStatus("USB permission denied")
            }
        }

        attachReceiver = usbHost.registerAttachReceiver { device ->
            setStatus("iPhone attached")
            if (awaitingCarPlayReattach) requestPermissionForCarPlay(device) else requestPermission(device)
        }

        val device = usbHost.discover().firstOrNull()
        if (device == null) setStatus("Connect iPhone by USB") else requestPermission(device)
    }

    private fun requestPermission(device: UsbDevice) {
        when (usbHost.requestPermission(device)) {
            is IphoneUsbHost.PermissionRequest.AlreadyGranted -> beginUsb(device)
            is IphoneUsbHost.PermissionRequest.Requested -> setStatus("Waiting for USB permission")
        }
    }

    private fun requestPermissionForCarPlay(device: UsbDevice) {
        when (usbHost.requestPermission(device)) {
            is IphoneUsbHost.PermissionRequest.AlreadyGranted -> openCarPlayUsb(device)
            is IphoneUsbHost.PermissionRequest.Requested -> setStatus("Waiting for CarPlay USB permission")
        }
    }

    private fun beginUsb(device: UsbDevice) {
        setStatus("Switching iPhone to CarPlay USB mode")
        usbHost.requestCarPlayReenumerationAsync(device, io) { result ->
            when (result) {
                is IphoneUsbHost.TransitionResult.ReenumerationRequested -> {
                    awaitingCarPlayReattach = true
                    setStatus("Waiting for iPhone CarPlay USB mode")
                }
                is IphoneUsbHost.TransitionResult.Failed ->
                    setStatus("USB setup failed: ${result.error.message ?: "unknown"}")
            }
        }
    }

    private fun openCarPlayUsb(device: UsbDevice) {
        usbHost.openIap2UsbSessionAsync(device, io) { result ->
            when (result) {
                is IphoneUsbHost.Iap2SessionResult.Connected -> {
                    awaitingCarPlayReattach = false
                    pendingUsbSession?.close()
                    pendingUsbSession = result.session
                    pendingUsbDevice = device
                    setStatus("USBMUX connected")
                    maybeStartController()
                }
                is IphoneUsbHost.Iap2SessionResult.Failed ->
                    setStatus("USBMUX failed: ${result.error.message ?: "unknown"}")
            }
        }
    }

    @Synchronized
    private fun maybeStartController() {
        if (controller != null) return
        val session = pendingUsbSession ?: return
        val device = pendingUsbDevice ?: return
        val surface = renderSurface ?: return
        val vpn = vpnService ?: return
        if (renderWidth <= 0 || renderHeight <= 0) return

        pendingUsbSession = null
        pendingUsbDevice = null
        controller = CrvWiredCarPlayController(
            context = this,
            usbManager = usbManager,
            vpn = vpn,
            surface = surface,
            displayWidth = renderWidth,
            displayHeight = renderHeight,
            report = ::setStatus,
        ).also {
            setStatus("Starting wired CarPlay")
            it.start(device, session)
        }
    }

    private fun setStatus(message: String) {
        runOnUiThread { status.text = message }
    }

    override fun onSurfaceTextureAvailable(texture: SurfaceTexture, width: Int, height: Int) {
        renderSurface?.release()
        renderSurface = Surface(texture)
        renderWidth = width
        renderHeight = height
        maybeStartController()
    }

    override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, width: Int, height: Int) {
        renderWidth = width
        renderHeight = height
    }

    override fun onSurfaceTextureUpdated(texture: SurfaceTexture) = Unit

    override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean {
        controller?.close()
        controller = null
        renderSurface?.release()
        renderSurface = null
        return true
    }

    override fun onDestroy() {
        controller?.close()
        controller = null
        pendingUsbSession?.close()
        pendingUsbSession = null
        permissionReceiver?.close()
        attachReceiver?.close()
        if (vpnBound) {
            runCatching { unbindService(vpnConnection) }
            vpnBound = false
        }
        renderSurface?.release()
        renderSurface = null
        io.shutdownNow()
        super.onDestroy()
    }

    private companion object {
        const val REQUEST_VPN = 4201
    }
}
