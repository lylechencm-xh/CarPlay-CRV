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

/**
 * 2021 Honda CR-V / Android 4.4 wired CarPlay entry point.
 *
 * Platform APIs only: no Compose, Media3, Android Auto or wireless path.
 */
class CrvCarPlayActivity : Activity(), TextureView.SurfaceTextureListener {
    private lateinit var usbManager: UsbManager
    private lateinit var usbHost: IphoneUsbHost
    private lateinit var video: TextureView
    private lateinit var status: TextView

    private val usbIo: ExecutorService =
        Executors.newSingleThreadExecutor { task -> Thread(task, "crv-usb").apply { isDaemon = true } }

    private var permissionReceiver: Closeable? = null
    private var attachReceiver: Closeable? = null
    private var pendingUsbSession: Iap2UsbSession? = null

    @Volatile private var awaitingCarPlayReattach = false
    private var initialUsbStarted = false

    private var videoSurface: Surface? = null
    private var surfaceWidth = 0
    private var surfaceHeight = 0

    private var vpnReady = false
    private var vpnBound = false
    private var vpnService: CarPlayVpnService? = null
    private var controller: CrvWiredCarPlayController? = null

    private val vpnConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            vpnService = (binder as CarPlayVpnService.LocalBinder).service
            setStatus("CarPlay network service ready")
            maybeStartInitialUsb()
            maybeStartController()
        }

        override fun onServiceDisconnected(name: ComponentName) {
            vpnService = null
            controller?.close()
            controller = null
            setStatus("CarPlay network service disconnected")
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
                handleTouch(view, event)
                true
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

        usbManager = getSystemService(USB_SERVICE) as UsbManager
        usbHost = IphoneUsbHost(this, usbManager, IphoneUsbMatcher.appleVendor())

        permissionReceiver = usbHost.registerPermissionReceiver { result ->
            when (result) {
                is IphoneUsbHost.PermissionResult.Granted -> {
                    if (awaitingCarPlayReattach) {
                        openCarPlayUsb(result.device)
                    } else {
                        beginUsb(result.device)
                    }
                }
                is IphoneUsbHost.PermissionResult.Denied ->
                    setStatus("USB permission denied")
            }
        }

        attachReceiver = usbHost.registerAttachReceiver { device ->
            setStatus("iPhone attached")
            if (!prerequisitesReady()) {
                setStatus("iPhone detected; preparing CarPlay network")
                return@registerAttachReceiver
            }
            if (awaitingCarPlayReattach) {
                requestPermissionForCarPlay(device)
            } else {
                requestPermission(device)
            }
        }

        vpnBound = bindService(
            Intent(this, CarPlayVpnService::class.java),
            vpnConnection,
            Context.BIND_AUTO_CREATE,
        )
        if (!vpnBound) {
            setStatus("Could not start CarPlay network service")
        }

        requestVpnPermission()
    }

    private fun requestVpnPermission() {
        val prepare = CarPlayVpnService.prepare(this)
        if (prepare == null) {
            vpnReady = true
            maybeStartInitialUsb()
            return
        }
        setStatus("Approve CarPlay network connection")
        startActivityForResult(prepare, REQUEST_VPN)
    }

    @Deprecated("Deprecated in Android API 30; required for Android 4.4")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_VPN) return
        vpnReady = resultCode == RESULT_OK
        if (vpnReady) {
            setStatus("CarPlay network approved")
            maybeStartInitialUsb()
            maybeStartController()
        } else {
            setStatus("CarPlay network permission denied")
        }
    }

    private fun prerequisitesReady(): Boolean =
        vpnReady && vpnService != null && videoSurface != null

    private fun maybeStartInitialUsb() {
        if (initialUsbStarted || !prerequisitesReady()) return
        initialUsbStarted = true

        val device = usbHost.discover().firstOrNull()
        if (device == null) {
            setStatus("Connect iPhone by USB")
        } else {
            requestPermission(device)
        }
    }

    private fun requestPermission(device: UsbDevice) {
        when (usbHost.requestPermission(device)) {
            is IphoneUsbHost.PermissionRequest.AlreadyGranted -> beginUsb(device)
            is IphoneUsbHost.PermissionRequest.Requested ->
                setStatus("Waiting for USB permission")
        }
    }

    private fun requestPermissionForCarPlay(device: UsbDevice) {
        when (usbHost.requestPermission(device)) {
            is IphoneUsbHost.PermissionRequest.AlreadyGranted -> openCarPlayUsb(device)
            is IphoneUsbHost.PermissionRequest.Requested ->
                setStatus("Waiting for CarPlay USB permission")
        }
    }

    private fun beginUsb(device: UsbDevice) {
        setStatus("Switching iPhone to CarPlay USB mode")
        usbHost.requestCarPlayReenumerationAsync(device, usbIo) { result ->
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
        setStatus("Opening CarPlay USB data path")
        usbHost.openIap2UsbSessionAsync(device, usbIo) { result ->
            when (result) {
                is IphoneUsbHost.Iap2SessionResult.Connected -> {
                    awaitingCarPlayReattach = false
                    pendingUsbSession?.close()
                    pendingUsbSession = result.session
                    pendingDevice = device
                    setStatus("USBMUX connected")
                    maybeStartController()
                }
                is IphoneUsbHost.Iap2SessionResult.Failed ->
                    setStatus("USBMUX failed: ${result.error.message ?: "unknown"}")
            }
        }
    }

    @Volatile private var pendingDevice: UsbDevice? = null

    private fun maybeStartController() {
        val service = vpnService ?: return
        val surface = videoSurface ?: return
        val device = pendingDevice ?: return
        val session = pendingUsbSession ?: return
        if (!vpnReady || controller != null) return

        pendingUsbSession = null
        pendingDevice = null

        val next = CrvWiredCarPlayController(
            context = this,
            usbManager = usbManager,
            vpn = service,
            surface = surface,
            displayWidth = surfaceWidth.coerceAtLeast(1),
            displayHeight = surfaceHeight.coerceAtLeast(1),
            report = ::setStatus,
        )
        controller = next
        setStatus("Starting CarPlay protocol")
        next.start(device, session)
    }

    private fun handleTouch(view: TextureView, event: MotionEvent) {
        val width = view.width
        val height = view.height
        if (width <= 0 || height <= 0) return

        val down = when (event.actionMasked) {
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> false
            else -> true
        }
        controller?.sendTouch(
            x = event.x.toDouble() / width.toDouble(),
            y = event.y.toDouble() / height.toDouble(),
            down = down,
        )
    }

    private fun setStatus(message: String) {
        runOnUiThread { status.text = message }
    }

    override fun onSurfaceTextureAvailable(texture: SurfaceTexture, width: Int, height: Int) {
        videoSurface?.release()
        videoSurface = Surface(texture)
        surfaceWidth = width
        surfaceHeight = height
        maybeStartInitialUsb()
        maybeStartController()
    }

    override fun onSurfaceTextureSizeChanged(
        texture: SurfaceTexture,
        width: Int,
        height: Int,
    ) {
        surfaceWidth = width
        surfaceHeight = height
    }

    override fun onSurfaceTextureUpdated(texture: SurfaceTexture) = Unit

    override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean {
        controller?.close()
        controller = null
        videoSurface?.release()
        videoSurface = null
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

        videoSurface?.release()
        videoSurface = null

        usbIo.shutdownNow()
        super.onDestroy()
    }

    companion object {
        private const val REQUEST_VPN = 4101
    }
}
