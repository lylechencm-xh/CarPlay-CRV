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
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.Surface
import android.view.TextureView
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import com.shilapi.xcertplay.network.CarPlayVpnService
import com.shilapi.xcertplay.transport.Iap2UsbSession
import com.shilapi.xcertplay.transport.IphoneCarPlayConfiguration
import com.shilapi.xcertplay.transport.IphoneUsbHost
import com.shilapi.xcertplay.transport.IphoneUsbMatcher
import java.io.Closeable
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * 2021 Honda CR-V / Android 4.4 wired CarPlay entry point.
 *
 * Platform-only UI:
 * iPhone USB -> USBMUX -> Lockdown/iAP2/MFi -> NCM/VPN -> AirPlay -> MediaCodec/AudioTrack.
 */
class CrvCarPlayActivity : Activity(), TextureView.SurfaceTextureListener {
    private lateinit var usbManager: UsbManager
    private lateinit var usbHost: IphoneUsbHost
    private lateinit var video: TextureView
    private lateinit var status: TextView
    private lateinit var diagnostics: CrvDiagnostics

    private val io: ExecutorService = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private var permissionReceiver: Closeable? = null
    private var attachReceiver: Closeable? = null
    private var detachReceiver: Closeable? = null

    @Volatile private var awaitingCarPlayReattach = false
    @Volatile private var openCarPlayAfterPermission = false
    private val usbTransitionGeneration = AtomicInteger(0)
    private var reconnectGeneration = 0
    private var reconnectAttempts = 0
    private var destroyed = false
    private var pendingDevice: UsbDevice? = null
    private var pendingUsbSession: Iap2UsbSession? = null

    private var videoSurface: Surface? = null
    private var surfaceWidth = Crv2021Config.CARPLAY_WIDTH
    private var surfaceHeight = Crv2021Config.CARPLAY_HEIGHT

    private var vpnService: CarPlayVpnService? = null
    private var vpnBound = false
    private var controller: CrvWiredCarPlayController? = null

    private val vpnConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            vpnService = (binder as CarPlayVpnService.LocalBinder).service
            vpnBound = true
            setStatus("CarPlay network ready")
            maybeStartCarPlay()
        }

        override fun onServiceDisconnected(name: ComponentName) {
            vpnBound = false
            vpnService = null
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
            isClickable = true
            setOnTouchListener { view, event ->
                val width = view.width.coerceAtLeast(1)
                val height = view.height.coerceAtLeast(1)
                val x = event.x.toDouble() / width.toDouble()
                val y = event.y.toDouble() / height.toDouble()
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN,
                    MotionEvent.ACTION_MOVE -> controller?.sendTouch(x, y, true)
                    MotionEvent.ACTION_UP -> {
                        controller?.sendTouch(x, y, false)
                        view.performClick()
                    }
                    MotionEvent.ACTION_CANCEL -> controller?.sendTouch(x, y, false)
                }
                true
            }
        }

        status = TextView(this).apply {
            text = getString(R.string.status_starting)
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

        diagnostics = CrvDiagnostics(this)
        diagnostics.log("app started api=" + android.os.Build.VERSION.SDK_INT)
        usbManager = getSystemService(Context.USB_SERVICE) as UsbManager
        usbHost = IphoneUsbHost(this, usbManager, IphoneUsbMatcher.appleVendor())

        permissionReceiver = usbHost.registerPermissionReceiver { result ->
            when (result) {
                is IphoneUsbHost.PermissionResult.Granted -> {
                    if (openCarPlayAfterPermission) {
                        openCarPlayAfterPermission = false
                        openCarPlayUsb(result.device)
                    } else {
                        beginUsb(result.device)
                    }
                }
                is IphoneUsbHost.PermissionResult.Denied -> {
                    openCarPlayAfterPermission = false
                    awaitingCarPlayReattach = false
                    usbTransitionGeneration.incrementAndGet()
                    reportStatus("USB permission denied")
                }
            }
        }

        attachReceiver = usbHost.registerAttachReceiver { device ->
            reconnectAttempts = 0
            reconnectGeneration++
            reportStatus("iPhone attached")
            if (awaitingCarPlayReattach) {
                awaitingCarPlayReattach = false
                usbTransitionGeneration.incrementAndGet()
                requestPermissionForCarPlay(device)
            } else {
                requestPermission(device)
            }
        }
        detachReceiver = usbHost.registerDetachReceiver {
            if (!awaitingCarPlayReattach) {
                runOnUiThread {
                    controller?.close()
                    controller = null
                    pendingUsbSession?.close()
                    pendingUsbSession = null
                    pendingDevice = null
                    openCarPlayAfterPermission = false
                    reconnectAttempts = 0
                    reconnectGeneration++
                    reportStatus("iPhone disconnected")
                }
            } else {
                reportStatus("iPhone switching USB mode")
            }
        }

        prepareVpn()

        val device = usbHost.discover().firstOrNull()
        if (device == null) setStatus("Connect iPhone by USB")
        else requestPermission(device)
    }

    private fun prepareVpn() {
        val consent = CarPlayVpnService.prepare(this)
        if (consent == null) {
            bindVpn()
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
            bindVpn()
        } else {
            setStatus("CarPlay network permission required")
        }
    }

    private fun bindVpn() {
        if (vpnBound) return
        val intent = Intent(this, CarPlayVpnService::class.java)
        if (!bindService(intent, vpnConnection, Context.BIND_AUTO_CREATE)) {
            setStatus("Could not start CarPlay network service")
        }
    }

    private fun requestPermission(device: UsbDevice) {
        openCarPlayAfterPermission = false
        when (usbHost.requestPermission(device)) {
            is IphoneUsbHost.PermissionRequest.AlreadyGranted -> beginUsb(device)
            is IphoneUsbHost.PermissionRequest.Requested ->
                setStatus("Waiting for USB permission")
        }
    }

    private fun requestPermissionForCarPlay(device: UsbDevice) {
        openCarPlayAfterPermission = true
        when (usbHost.requestPermission(device)) {
            is IphoneUsbHost.PermissionRequest.AlreadyGranted -> {
                openCarPlayAfterPermission = false
                openCarPlayUsb(device)
            }
            is IphoneUsbHost.PermissionRequest.Requested ->
                setStatus("Waiting for CarPlay USB permission")
        }
    }

    private fun beginUsb(device: UsbDevice) {
        openCarPlayAfterPermission = false
        awaitingCarPlayReattach = true
        val generation = usbTransitionGeneration.incrementAndGet()
        reportStatus("Switching iPhone to CarPlay USB mode")
        usbHost.requestCarPlayReenumerationAsync(device, io) { result ->
            when (result) {
                is IphoneUsbHost.TransitionResult.ReenumerationRequested -> {
                    reportStatus("Waiting for iPhone CarPlay USB mode")
                    // Honda Android 4.4 head units can suppress the USB ATTACHED broadcast
                    // after Apple's 0x52 mode switch. Poll UsbManager as a fallback instead
                    // of waiting only for the broadcast.
                    pollCarPlayReenumeration(generation, System.currentTimeMillis())
                }
                is IphoneUsbHost.TransitionResult.Failed -> {
                    awaitingCarPlayReattach = false
                    usbTransitionGeneration.incrementAndGet()
                    reportStatus("USB setup failed: ${result.error.message ?: "unknown"}")
                }
            }
        }
    }

    private fun pollCarPlayReenumeration(generation: Int, startedAtMillis: Long) {
        mainHandler.postDelayed(object : Runnable {
            override fun run() {
                if (
                    destroyed ||
                    !awaitingCarPlayReattach ||
                    generation != usbTransitionGeneration.get()
                ) return

                val device = usbHost.discover().firstOrNull()
                if (device != null && IphoneCarPlayConfiguration.hasActiveCarPlayLayout(device)) {
                    diagnostics.log("CarPlay USB mode detected by polling fallback")
                    awaitingCarPlayReattach = false
                    usbTransitionGeneration.incrementAndGet()
                    requestPermissionForCarPlay(device)
                    return
                }

                if (System.currentTimeMillis() - startedAtMillis >= USB_REENUMERATION_TIMEOUT_MILLIS) {
                    awaitingCarPlayReattach = false
                    reportStatus("CarPlay USB mode switch timed out; reconnect iPhone")
                    return
                }

                mainHandler.postDelayed(this, USB_REENUMERATION_POLL_MILLIS)
            }
        }, USB_REENUMERATION_POLL_MILLIS)
    }

    private fun openCarPlayUsb(device: UsbDevice) {
        setStatus("Opening CarPlay USB data paths")
        usbHost.openIap2UsbSessionAsync(device, io) { result ->
            when (result) {
                is IphoneUsbHost.Iap2SessionResult.Connected -> {
                    openCarPlayAfterPermission = false
                    awaitingCarPlayReattach = false
                    usbTransitionGeneration.incrementAndGet()
                    synchronized(this) {
                        pendingUsbSession?.close()
                        pendingUsbSession = result.session
                        pendingDevice = device
                    }
                    setStatus("USBMUX connected")
                    runOnUiThread { maybeStartCarPlay() }
                }
                is IphoneUsbHost.Iap2SessionResult.Failed -> {
                    openCarPlayAfterPermission = false
                    awaitingCarPlayReattach = false
                    usbTransitionGeneration.incrementAndGet()
                    reportStatus("USBMUX failed: ${result.error.message ?: "unknown"}")
                }
            }
        }
    }

    @Synchronized
    private fun maybeStartCarPlay() {
        if (controller != null) return
        val device = pendingDevice ?: return
        val usb = pendingUsbSession ?: return
        val vpn = vpnService ?: return
        val surface = videoSurface ?: return

        pendingDevice = null
        pendingUsbSession = null

        val next = CrvWiredCarPlayController(
            context = this,
            usbManager = usbManager,
            vpn = vpn,
            surface = surface,
            displayWidth = surfaceWidth,
            displayHeight = surfaceHeight,
            report = ::reportStatus,
            onStopped = {
                runOnUiThread {
                    controller = null
                    scheduleReconnect()
                }
            },
        )
        controller = next
        setStatus("Starting wired CarPlay")
        next.start(device, usb)
    }

    private fun reportStatus(message: String) {
        if (message == "CarPlay active") {
            mainHandler.post {
                if (!destroyed) {
                    reconnectAttempts = 0
                    reconnectGeneration++
                }
            }
        }
        diagnostics.log(message)
        setStatus(message)
    }

    private fun scheduleReconnect() {
        if (destroyed || videoSurface == null || vpnService == null) return
        if (reconnectAttempts >= MAX_AUTOMATIC_RECONNECTS) {
            reportStatus("CarPlay stopped; reconnect iPhone to retry")
            return
        }
        val generation = ++reconnectGeneration
        reconnectAttempts++
        reportStatus("CarPlay stopped; retrying USB session ${reconnectAttempts}/$MAX_AUTOMATIC_RECONNECTS")
        mainHandler.postDelayed({
            if (
                destroyed ||
                generation != reconnectGeneration ||
                controller != null ||
                pendingUsbSession != null ||
                videoSurface == null ||
                vpnService == null
            ) {
                return@postDelayed
            }
            val device = usbHost.discover().firstOrNull()
            if (device == null) {
                reportStatus("Connect iPhone by USB")
                return@postDelayed
            }
            if (IphoneCarPlayConfiguration.hasActiveCarPlayLayout(device)) {
                awaitingCarPlayReattach = false
                requestPermissionForCarPlay(device)
            } else {
                awaitingCarPlayReattach = false
                requestPermission(device)
            }
        }, AUTOMATIC_RECONNECT_DELAY_MILLIS)
    }

    private fun setStatus(message: String) {
        runOnUiThread {
            status.text = message
            status.visibility = if (message == "CarPlay active") android.view.View.GONE
            else android.view.View.VISIBLE
        }
    }

    override fun onSurfaceTextureAvailable(texture: SurfaceTexture, width: Int, height: Int) {
        videoSurface?.release()
        videoSurface = Surface(texture)
        surfaceWidth = width.coerceAtLeast(1)
        surfaceHeight = height.coerceAtLeast(1)
        maybeStartCarPlay()
    }

    override fun onSurfaceTextureSizeChanged(
        texture: SurfaceTexture,
        width: Int,
        height: Int,
    ) {
        surfaceWidth = width.coerceAtLeast(1)
        surfaceHeight = height.coerceAtLeast(1)
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
        destroyed = true
        openCarPlayAfterPermission = false
        reconnectGeneration++
        usbTransitionGeneration.incrementAndGet()
        mainHandler.removeCallbacksAndMessages(null)

        permissionReceiver?.close()
        permissionReceiver = null
        attachReceiver?.close()
        attachReceiver = null
        detachReceiver?.close()
        detachReceiver = null

        controller?.close()
        controller = null

        pendingUsbSession?.close()
        pendingUsbSession = null
        pendingDevice = null

        if (vpnBound) {
            runCatching { unbindService(vpnConnection) }
            vpnBound = false
        }
        vpnService = null

        videoSurface?.release()
        videoSurface = null

        if (::diagnostics.isInitialized) diagnostics.log("app stopped")
        io.shutdownNow()
        super.onDestroy()
    }

    companion object {
        private const val REQUEST_VPN = 1001
        private const val USB_REENUMERATION_TIMEOUT_MILLIS = 30_000L
        private const val USB_REENUMERATION_POLL_MILLIS = 500L
        private const val AUTOMATIC_RECONNECT_DELAY_MILLIS = 2_000L
        private const val MAX_AUTOMATIC_RECONNECTS = 3
    }
}
