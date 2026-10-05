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
    private lateinit var modeButton: TextView
    private var connectionMode = CrvConnectionMode.WIRED
    @Volatile private var connectionStatusActive = false

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
            connectionStatus("CarPlay network ready")
            maybeStartCarPlay()
        }

        override fun onServiceDisconnected(name: ComponentName) {
            vpnBound = false
            vpnService = null
            controller?.close()
            controller = null
            connectionError("CarPlay network service stopped")
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

        modeButton = TextView(this).apply {
            text = "Mode: USB"
            setTextColor(Color.WHITE)
            setBackgroundColor(0x66000000)
            setPadding(18, 12, 18, 12)
            setOnClickListener {
                connectionMode = if (connectionMode == CrvConnectionMode.WIRED) {
                    CrvConnectionMode.WIFI_HANDOFF
                } else {
                    CrvConnectionMode.WIRED
                }
                text = if (connectionMode == CrvConnectionMode.WIFI_HANDOFF) {
                    "Mode: Wi-Fi handoff"
                } else {
                    "Mode: USB"
                }
                controller?.close()
                controller = null
                pendingUsbSession?.close()
                pendingUsbSession = null
                pendingDevice = null
                reconnectAttempts = 0
                reconnectGeneration++
                val selected = usbHost.discover().firstOrNull()
                if (selected != null) {
                    beginConnectionStatus(
                        if (connectionMode == CrvConnectionMode.WIFI_HANDOFF) {
                            "Starting Wi-Fi CarPlay handoff"
                        } else {
                            "Starting USB CarPlay"
                        },
                    )
                    requestPermission(selected)
                } else {
                    hideConnectionStatus()
                }
            }
        }

        status = TextView(this).apply {
            text = ""
            visibility = android.view.View.GONE
            setTextColor(Color.WHITE)
            setBackgroundColor(0x99000000.toInt())
            gravity = Gravity.CENTER
            setPadding(18, 12, 18, 12)
        }

        setContentView(FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            addView(video, FrameLayout.LayoutParams(-1, -1))
            addView(status, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
            addView(modeButton, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.END))
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
                        routeGrantedDevice(result.device)
                    }
                }
                is IphoneUsbHost.PermissionResult.Denied -> {
                    openCarPlayAfterPermission = false
                    awaitingCarPlayReattach = false
                    usbTransitionGeneration.incrementAndGet()
                    connectionError("USB permission denied")
                }
            }
        }

        attachReceiver = usbHost.registerAttachReceiver { device ->
            reconnectAttempts = 0
            reconnectGeneration++
            beginConnectionStatus("iPhone attached")
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
                    connectionError("iPhone disconnected")
                }
            } else {
                reportStatus("iPhone switching USB mode")
            }
        }

        prepareVpn()

        val device = usbHost.discover().firstOrNull()
        if (device == null) {
            hideConnectionStatus()
        } else {
            beginConnectionStatus("iPhone detected")
            requestPermission(device)
        }
    }

    private fun prepareVpn() {
        val consent = CarPlayVpnService.prepare(this)
        if (consent == null) {
            bindVpn()
        } else {
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
            connectionError("CarPlay network permission required")
        }
    }

    private fun bindVpn() {
        if (vpnBound) return
        val intent = Intent(this, CarPlayVpnService::class.java)
        if (!bindService(intent, vpnConnection, Context.BIND_AUTO_CREATE)) {
            connectionError("Could not start CarPlay network service")
        }
    }

    private fun requestPermission(device: UsbDevice) {
        openCarPlayAfterPermission = false
        when (usbHost.requestPermission(device)) {
            is IphoneUsbHost.PermissionRequest.AlreadyGranted -> routeGrantedDevice(device)
            is IphoneUsbHost.PermissionRequest.Requested ->
                connectionStatus("Waiting for USB permission")
        }
    }

    private fun routeGrantedDevice(device: UsbDevice) {
        if (IphoneCarPlayConfiguration.hasActiveCarPlayLayout(device)) {
            awaitingCarPlayReattach = false
            usbTransitionGeneration.incrementAndGet()
            reportStatus("iPhone already in CarPlay USB mode")
            openCarPlayUsb(device)
        } else {
            beginUsb(device)
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
                connectionStatus("Waiting for CarPlay USB permission")
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
                    mainHandler.post {
                        pollForCarPlayReattach(
                            generation = generation,
                            deadlineMillis = android.os.SystemClock.elapsedRealtime() +
                                USB_REENUMERATION_TIMEOUT_MILLIS,
                        )
                    }
                }
                is IphoneUsbHost.TransitionResult.Failed -> {
                    awaitingCarPlayReattach = false
                    usbTransitionGeneration.incrementAndGet()
                    connectionError("USB setup failed: ${result.error.message ?: "unknown"}")
                }
            }
        }
    }

    private fun pollForCarPlayReattach(generation: Int, deadlineMillis: Long) {
        if (
            destroyed ||
            !awaitingCarPlayReattach ||
            generation != usbTransitionGeneration.get()
        ) {
            return
        }

        val activeDevice = usbHost.discover().firstOrNull {
            IphoneCarPlayConfiguration.hasActiveCarPlayLayout(it)
        }
        if (activeDevice != null) {
            awaitingCarPlayReattach = false
            usbTransitionGeneration.incrementAndGet()
            reportStatus("CarPlay USB mode detected")
            requestPermissionForCarPlay(activeDevice)
            return
        }

        if (android.os.SystemClock.elapsedRealtime() >= deadlineMillis) {
            awaitingCarPlayReattach = false
            usbTransitionGeneration.incrementAndGet()
            connectionError("CarPlay USB mode switch timed out; reconnect iPhone")
            return
        }

        mainHandler.postDelayed(
            { pollForCarPlayReattach(generation, deadlineMillis) },
            USB_REENUMERATION_POLL_MILLIS,
        )
    }

    private fun openCarPlayUsb(device: UsbDevice) {
        connectionStatus("Opening CarPlay USB data paths")
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
                    connectionStatus("USBMUX connected")
                    runOnUiThread { maybeStartCarPlay() }
                }
                is IphoneUsbHost.Iap2SessionResult.Failed -> {
                    openCarPlayAfterPermission = false
                    awaitingCarPlayReattach = false
                    usbTransitionGeneration.incrementAndGet()
                    connectionError("USBMUX failed: ${result.error.message ?: "unknown"}")
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
            mode = connectionMode,
            onStopped = {
                runOnUiThread {
                    controller = null
                    scheduleReconnect()
                }
            },
        )
        controller = next
        connectionStatus(
            if (connectionMode == CrvConnectionMode.WIFI_HANDOFF) {
                "Starting Wi-Fi CarPlay handoff"
            } else {
                "Starting wired CarPlay"
            },
        )
        next.start(device, usb)
    }

    private fun reportStatus(message: String) {
        val stage = stageFor(message)
        if (message == "CarPlay active") {
            diagnostics.log(CrvConnectionStage.CARPLAY_ACTIVE, message)
            mainHandler.post {
                if (!destroyed) {
                    reconnectAttempts = 0
                    reconnectGeneration++
                    connectionStatusActive = false
                    status.text = ""
                    status.visibility = android.view.View.GONE
                }
            }
            return
        }

        if (isConnectionError(message)) {
            connectionError(message)
        } else {
            diagnostics.log(stage, message)
            connectionStatus(message)
        }
    }

    private fun scheduleReconnect() {
        if (destroyed || videoSurface == null || vpnService == null) return
        if (reconnectAttempts >= MAX_AUTOMATIC_RECONNECTS) {
            connectionError("CarPlay stopped; reconnect iPhone to retry")
            return
        }
        val generation = ++reconnectGeneration
        reconnectAttempts++
        val delayMillis = reconnectDelayMillis(reconnectAttempts)
        diagnostics.log(
            CrvConnectionStage.RETRYING,
            "retry=$reconnectAttempts/$MAX_AUTOMATIC_RECONNECTS delayMs=$delayMillis",
        )
        beginConnectionStatus(
            "CarPlay stopped; retrying session ${reconnectAttempts}/$MAX_AUTOMATIC_RECONNECTS",
        )
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
                connectionError("iPhone not detected; reconnect USB to retry")
                return@postDelayed
            }
            if (IphoneCarPlayConfiguration.hasActiveCarPlayLayout(device)) {
                awaitingCarPlayReattach = false
                requestPermissionForCarPlay(device)
            } else {
                awaitingCarPlayReattach = false
                requestPermission(device)
            }
        }, delayMillis)
    }

    private fun beginConnectionStatus(message: String) {
        connectionStatusActive = true
        connectionStatus(message)
    }

    private fun connectionStatus(message: String) {
        if (!connectionStatusActive) return
        runOnUiThread {
            if (destroyed) return@runOnUiThread
            status.text = message
            status.visibility = android.view.View.VISIBLE
        }
    }

    private fun connectionError(message: String) {
        diagnostics.log(CrvConnectionStage.ERROR, message)
        connectionStatusActive = true
        runOnUiThread {
            if (destroyed) return@runOnUiThread
            status.text = "Error: $message"
            status.visibility = android.view.View.VISIBLE
        }
    }

    private fun hideConnectionStatus() {
        connectionStatusActive = false
        runOnUiThread {
            if (destroyed) return@runOnUiThread
            status.text = ""
            status.visibility = android.view.View.GONE
        }
    }

    private fun isConnectionError(message: String): Boolean {
        val value = message.lowercase(java.util.Locale.US)
        return ERROR_STATUS_WORDS.any(value::contains)
    }

    private fun reconnectDelayMillis(attempt: Int): Long {
        val index = (attempt - 1).coerceIn(0, RECONNECT_DELAYS_MILLIS.lastIndex)
        return RECONNECT_DELAYS_MILLIS[index]
    }

    private fun stageFor(message: String): CrvConnectionStage {
        val value = message.lowercase(java.util.Locale.US)
        return when {
            isConnectionError(message) -> CrvConnectionStage.ERROR
            "carplay active" in value -> CrvConnectionStage.CARPLAY_ACTIVE
            "wi-fi hotspot" in value -> CrvConnectionStage.WIFI_HOTSPOT
            "wi-fi" in value -> CrvConnectionStage.WIFI_HANDOFF
            "airplay" in value && ("accepted" in value || "connected" in value) ->
                CrvConnectionStage.AIRPLAY_CONNECTED
            "airplay" in value && ("listening" in value || "ready" in value) ->
                CrvConnectionStage.AIRPLAY_LISTENING
            "mfi" in value || "authentication" in value -> CrvConnectionStage.MFI
            "lockdown" in value || "pairing" in value -> CrvConnectionStage.LOCKDOWN
            "iap2" in value -> CrvConnectionStage.IAP2
            "ncm" in value -> CrvConnectionStage.NCM
            "usbmux" in value -> CrvConnectionStage.USBMUX_READY
            "network ready" in value -> CrvConnectionStage.NETWORK_READY
            "permission" in value -> CrvConnectionStage.USB_PERMISSION
            "switching" in value || "usb mode" in value -> CrvConnectionStage.USB_REENUMERATION
            "iphone" in value || "usb" in value -> CrvConnectionStage.USB_DETECTED
            else -> CrvConnectionStage.IDLE
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
        private const val USB_REENUMERATION_TIMEOUT_MILLIS = 15_000L
        private const val USB_REENUMERATION_POLL_MILLIS = 500L
        private val RECONNECT_DELAYS_MILLIS = longArrayOf(1_000L, 2_000L, 4_000L, 8_000L)
        private const val MAX_AUTOMATIC_RECONNECTS = 4
        private val ERROR_STATUS_WORDS = listOf(
            "failed",
            "failure",
            "error",
            "denied",
            "timed out",
            "timeout",
            "stopped",
            "disconnected",
            "missing",
            "rejected",
            "unavailable",
            "could not",
        )
    }
}
