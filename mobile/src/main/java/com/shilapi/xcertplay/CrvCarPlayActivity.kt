package com.shilapi.xcertplay

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.graphics.Color
import android.graphics.SurfaceTexture
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
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
import kotlin.math.roundToInt

/**
 * 2021 Honda CR-V / Android 4.2.2 (API17) wired CarPlay entry point.
 *
 * Platform-only UI:
 * iPhone USB -> USBMUX -> Lockdown/iAP2/MFi -> NCM/VPN -> AirPlay -> MediaCodec/AudioTrack.
 */
class CrvCarPlayActivity : Activity(), TextureView.SurfaceTextureListener {
    private lateinit var usbManager: UsbManager
    private lateinit var usbHost: IphoneUsbHost
    private lateinit var video: TextureView
    private lateinit var home: FrameLayout
    private lateinit var heroTitle: TextView
    private lateinit var heroBody: TextView
    private lateinit var stageLabel: TextView
    private lateinit var stageCard: LinearLayout
    private lateinit var progressBar: LinearLayout
    private lateinit var connectButton: TextView
    private lateinit var disconnectButton: TextView
    private val progressDots = mutableListOf<TextView>()
    private lateinit var diagnostics: CrvDiagnostics
    private lateinit var modeButton: TextView
    private var connectionMode = CrvConnectionMode.WIRED
    @Volatile private var homeVisible = true
    @Volatile private var manualDisconnect = false
    private var displayedConnectionStage = CrvConnectionStage.IDLE
    private var lastProgressStep = 0

    private val io: ExecutorService = Executors.newSingleThreadExecutor()
    private val diagnosticIo: ExecutorService = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private var permissionReceiver: Closeable? = null
    private var attachReceiver: Closeable? = null
    private var detachReceiver: Closeable? = null

    @Volatile private var awaitingCarPlayReattach = false
    @Volatile private var openCarPlayAfterPermission = false
    private val usbTransitionGeneration = AtomicInteger(0)
    private var reconnectGeneration = 0
    private var reconnectAttempts = 0
    private var reconnectBlockedForMfi = false
    private var destroyed = false
    private var pendingDevice: UsbDevice? = null
    private var pendingUsbSession: Iap2UsbSession? = null

    private var videoSurface: Surface? = null
    private var surfaceWidth = Crv2021Config.CARPLAY_WIDTH
    private var surfaceHeight = Crv2021Config.CARPLAY_HEIGHT

    private var controller: CrvWiredCarPlayController? = null
    private var mediaCoreMonitor: CrvHondaMediaCoreMonitor? = null
    private lateinit var runtimeSnapshotProbe: CrvRuntimeSnapshotProbe
    private val snapshotReasons = HashSet<String>()

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
            text = "USB connection"
            setTextColor(Color.WHITE)
            textSize = 14f
            gravity = Gravity.CENTER
            background = cardBackground(CARD_COLOR, CARD_STROKE_COLOR)
            setPadding(dp(18), dp(12), dp(18), dp(12))
            isFocusable = true
            setOnClickListener {
                connectionMode = if (connectionMode == CrvConnectionMode.WIRED) {
                    CrvConnectionMode.WIFI_HANDOFF
                } else {
                    CrvConnectionMode.WIRED
                }
                text = if (connectionMode == CrvConnectionMode.WIFI_HANDOFF) {
                    "Wi-Fi test mode"
                } else {
                    "USB connection"
                }
                controller?.close()
                controller = null
                pendingUsbSession?.close()
                pendingUsbSession = null
                pendingDevice = null
                reconnectAttempts = 0
                reconnectBlockedForMfi = false
                reconnectGeneration++
                manualDisconnect = false
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

        heroTitle = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 30f
            typeface = Typeface.DEFAULT_BOLD
            text = HOME_READY_TITLE
        }

        heroBody = TextView(this).apply {
            setTextColor(HOME_BODY_COLOR)
            textSize = 15f
            text = HOME_READY_BODY
            setLineSpacing(dp(4).toFloat(), 1f)
        }

        stageLabel = TextView(this).apply {
            setTextColor(HOME_MUTED_COLOR)
            textSize = 14f
            text = "Step 1 of 4 · Connect phone"
        }

        progressBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        PROGRESS_STEPS.forEachIndexed { index, label ->
            val step = TextView(this).apply {
                text = "${index + 1}  $label"
                setTextColor(HOME_MUTED_COLOR)
                textSize = 14f
                gravity = Gravity.CENTER
                setPadding(dp(8), dp(12), dp(8), dp(12))
                background = cardBackground(HOME_BACKGROUND_COLOR, CARD_STROKE_COLOR)
            }
            progressDots.add(step)
            progressBar.addView(
                step,
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    if (index > 0) leftMargin = dp(8)
                },
            )
        }

        connectButton = TextView(this).apply {
            text = "Connect iPhone"
            setTextColor(Color.WHITE)
            textSize = 17f
            gravity = Gravity.CENTER
            setPadding(dp(24), dp(14), dp(24), dp(14))
            background = cardBackground(PRIMARY_BUTTON_COLOR, 0)
            isClickable = true
            isFocusable = true
            setOnClickListener { beginManualConnect() }
        }

        disconnectButton = TextView(this).apply {
            text = "Disconnect"
            setTextColor(HOME_BODY_COLOR)
            textSize = 15f
            gravity = Gravity.CENTER
            setPadding(dp(24), dp(12), dp(24), dp(12))
            background = cardBackground(0, SECONDARY_BUTTON_STROKE_COLOR)
            isClickable = true
            isFocusable = true
            setOnClickListener { beginManualDisconnect() }
        }

        val headerRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(
                TextView(this@CrvCarPlayActivity).apply {
                    text = getString(R.string.app_name)
                    setTextColor(Color.WHITE)
                    textSize = 20f
                    typeface = Typeface.DEFAULT_BOLD
                },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
            )
            if (!Crv2021Config.WIRED_ONLY) {
                addView(modeButton, LinearLayout.LayoutParams(-2, -2))
            }
        }

        stageCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(16), dp(20), dp(16))
            background = cardBackground(CARD_COLOR, CARD_STROKE_COLOR)
            addView(stageLabel, LinearLayout.LayoutParams(-1, -2))
            addView(
                progressBar,
                LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) },
            )
        }

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(40), dp(28), dp(40), dp(24))
            addView(headerRow, LinearLayout.LayoutParams(-1, -2))
            addView(heroTitle, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(24) })
            addView(heroBody, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
            addView(stageCard, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(22) })
            addView(
                connectButton,
                LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(18) },
            )
            addView(
                disconnectButton,
                LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) },
            )
        }

        home = FrameLayout(this).apply {
            setBackgroundColor(HOME_BACKGROUND_COLOR)
            addView(
                ScrollView(this@CrvCarPlayActivity).apply {
                    addView(content, ViewGroup.LayoutParams(-1, -2))
                },
                FrameLayout.LayoutParams(-1, -1),
            )
        }

        setContentView(FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            addView(video, FrameLayout.LayoutParams(-1, -1))
            addView(home, FrameLayout.LayoutParams(-1, -1))
        })

        diagnostics = CrvDiagnostics(this)
        mediaCoreMonitor = CrvHondaMediaCoreMonitor(applicationContext, diagnostics::log).also {
            it.start()
        }
        runtimeSnapshotProbe = CrvRuntimeSnapshotProbe(applicationContext)
        diagnostics.log("app started api=" + android.os.Build.VERSION.SDK_INT)
        val probeContext = applicationContext
        diagnosticIo.execute {
            runCatching { diagnostics.pruneOldLogs() }
            CrvSystemInfoProbe(probeContext).collect().forEach(diagnostics::log)
            CrvHondaPlatformProbe(probeContext).collect().forEach(diagnostics::log)
            runtimeSnapshotProbe.collect("app-start").forEach(diagnostics::log)
        }
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
            if (controller?.shouldKeepWirelessOnUsbDetach() == true) {
                reportStatus("USB attached; continuing Wi-Fi CarPlay")
                return@registerAttachReceiver
            }
            manualDisconnect = false
            reconnectAttempts = 0
            reconnectBlockedForMfi = false
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
                    usbTransitionGeneration.incrementAndGet()
                    if (controller?.shouldKeepWirelessOnUsbDetach() == true) {
                        reportStatus("USB removed; keeping Wi-Fi handoff")
                        return@runOnUiThread
                    }
                    controller?.close(CrvRecoveryTrigger.USB_DETACHED)
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
            connectionStatus("CarPlay network permission ready")
        } else {
            connectionStatus("Requesting CarPlay network permission")
            startActivityForResult(consent, REQUEST_VPN)
        }
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_VPN) return
        if (resultCode == RESULT_OK) {
            connectionStatus("CarPlay network permission granted")
            maybeStartCarPlay()
        } else {
            connectionError("CarPlay network permission required")
        }
    }

    private fun hasActiveCarPlayLayout(device: UsbDevice): Boolean {
        if (!usbManager.hasPermission(device)) {
            return IphoneCarPlayConfiguration.hasActiveCarPlayLayout(device)
        }
        val connection = runCatching { usbManager.openDevice(device) }.getOrNull()
            ?: return IphoneCarPlayConfiguration.hasActiveCarPlayLayout(device)
        return try {
            val rawDescriptors = connection.rawDescriptors
            IphoneCarPlayConfiguration.carPlayConfigurationValue(rawDescriptors) != null
        } catch (_: RuntimeException) {
            IphoneCarPlayConfiguration.hasActiveCarPlayLayout(device)
        } finally {
            connection.close()
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
        if (hasActiveCarPlayLayout(device)) {
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
            runOnUiThread {
                // Re-enumeration may attach a new device before this worker completes.
                // An old permission error must not cancel that new device's connection.
                if (destroyed || generation != usbTransitionGeneration.get()) return@runOnUiThread
                when (result) {
                    is IphoneUsbHost.TransitionResult.ReenumerationRequested -> {
                        reportStatus("Waiting for iPhone CarPlay USB mode")
                        pollForCarPlayReattach(
                            generation = generation,
                            deadlineMillis = android.os.SystemClock.elapsedRealtime() +
                                USB_REENUMERATION_TIMEOUT_MILLIS,
                        )
                    }
                    is IphoneUsbHost.TransitionResult.Failed -> {
                        awaitingCarPlayReattach = false
                        usbTransitionGeneration.incrementAndGet()
                        connectionError("USB setup failed: ${result.error.message ?: "unknown"}")
                    }
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
            hasActiveCarPlayLayout(it)
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
        val generation = usbTransitionGeneration.incrementAndGet()
        connectionStatus("Opening CarPlay USB data paths")
        usbHost.openIap2UsbSessionAsync(device, io) { result ->
            // Use Activity's UI dispatch so onDestroy's timer cancellation does not
            // discard a queued result before its USB session can be closed.
            runOnUiThread {
                if (destroyed || generation != usbTransitionGeneration.get()) {
                    if (result is IphoneUsbHost.Iap2SessionResult.Connected) result.session.close()
                    return@runOnUiThread
                }
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
                        connectionStatus("USB bulk data path open")
                        maybeStartCarPlay()
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
    }

    @Synchronized
    private fun maybeStartCarPlay() {
        if (controller != null) return
        val device = pendingDevice
        val usb = pendingUsbSession
        val surface = videoSurface
        if (device == null || usb == null || surface == null) {
            val waiting = mutableListOf<String>()
            if (device == null || usb == null) waiting += "USB session"
            if (surface == null) waiting += "display surface"
            if (pendingUsbSession != null) {
                connectionStatus("Waiting for " + waiting.joinToString(", "))
                diagnostics.log(
                    CrvConnectionStage.NETWORK_READY,
                    "controller waiting for " + waiting.joinToString(", "),
                )
            }
            return
        }

        pendingDevice = null
        pendingUsbSession = null

        lateinit var next: CrvWiredCarPlayController
        next = CrvWiredCarPlayController(
            context = this,
            usbManager = usbManager,
            surface = surface,
            displayWidth = surfaceWidth,
            displayHeight = surfaceHeight,
            report = ::reportStatus,
            recordProtocolTrace = diagnostics::log,
            reportFailure = diagnostics::logFailure,
            mode = connectionMode,
            onStopped = {
                runOnUiThread {
                    if (controller === next) {
                        controller = null
                        scheduleReconnect()
                    }
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
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { if (!destroyed) reportStatus(message) }
            return
        }
        if (destroyed) return
        if (isCrvUiDiagnostic(message)) {
            diagnostics.log(displayedConnectionStage, message)
            return
        }
        maybeCaptureRuntimeSnapshot(message)
        val stage = stageFor(message)
        val displayStage = monotonicConnectionStage(displayedConnectionStage, stage)
        displayedConnectionStage = displayStage
        val normalized = message.lowercase(java.util.Locale.US)
        if (
            "transport pre-auth verified" in normalized &&
            (
                "mfi identity missing" in normalized ||
                    "mfi identity invalid" in normalized
            )
        ) {
            reconnectBlockedForMfi = true
        }
        if (message == "CarPlay active") {
            diagnostics.log(CrvConnectionStage.CARPLAY_ACTIVE, message)
            connectionStatus(message)
            reconnectAttempts = 0
            reconnectGeneration++
            displayedConnectionStage = CrvConnectionStage.CARPLAY_ACTIVE
            renderHome(displayedConnectionStage, message)
            mainHandler.postDelayed(
                {
                    if (!destroyed && controller != null) showVideo()
                },
                STATUS_HIDE_AFTER_ACTIVE_MILLIS,
            )
            return
        }

        if (isConnectionError(message)) {
            connectionError(message)
        } else {
            diagnostics.log(stage, message)
            if (shouldReturnHome(message)) showHome()
            connectionStatus(message)
            renderHome(displayStage, message)
        }
    }

    private fun maybeCaptureRuntimeSnapshot(message: String) {
        if (destroyed || diagnosticIo.isShutdown) return
        val lower = message.lowercase(java.util.Locale.US)
        val reason = when {
            "opening cdc-ncm" in lower -> "before-ncm"
            "cdc-ncm ready" in lower -> "ncm-ready"
            "usbmux ready" in lower -> "usbmux-ready"
            "lockdown ready" in lower -> "lockdown-ready"
            "iap2 carkit channel ready" in lower -> "iap2-ready"
            "airplay listening" in lower -> "airplay-listening"
            "transport pre-auth ready" in lower -> "pre-auth-ready"
            "mfi" in lower && ("failed" in lower || "missing" in lower || "invalid" in lower) -> "mfi-failure"
            "carplay failed" in lower -> "carplay-failure"
            "carplay active" in lower -> "carplay-active"
            else -> null
        } ?: return
        synchronized(snapshotReasons) {
            if (!snapshotReasons.add(reason)) return
        }
        diagnosticIo.execute {
            runtimeSnapshotProbe.collect(reason).forEach(diagnostics::log)
        }
    }
    private fun scheduleReconnect() {
        if (destroyed || videoSurface == null) return
        if (manualDisconnect) {
            showHome()
            return
        }
        if (reconnectBlockedForMfi) {
            diagnostics.log(
                CrvConnectionStage.MFI,
                "automatic reconnect suppressed until MFi identity is provisioned",
            )
            beginConnectionStatus(
                "Waiting for MFi provisioning; reconnect iPhone after installing identity",
            )
            return
        }
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
                videoSurface == null
            ) {
                return@postDelayed
            }
            val device = usbHost.discover().firstOrNull()
            if (device == null) {
                connectionError("iPhone not detected; reconnect USB to retry")
                return@postDelayed
            }
            if (hasActiveCarPlayLayout(device)) {
                awaitingCarPlayReattach = false
                requestPermissionForCarPlay(device)
            } else {
                awaitingCarPlayReattach = false
                requestPermission(device)
            }
        }, delayMillis)
    }

    private fun beginConnectionStatus(message: String) {
        displayedConnectionStage = CrvConnectionStage.IDLE
        lastProgressStep = 0
        showHome()
        renderHome(CrvConnectionStage.IDLE, message)
    }

    private fun connectionStatus(message: String) {
        if (!homeVisible) return
        val candidate = stageFor(message)
        if (candidate != CrvConnectionStage.IDLE) {
            displayedConnectionStage =
                monotonicConnectionStage(displayedConnectionStage, candidate)
        }
        renderHome(displayedConnectionStage, message)
    }

    private fun connectionError(message: String) {
        diagnostics.log(CrvConnectionStage.ERROR, message)
        showHome()
        displayedConnectionStage = CrvConnectionStage.ERROR
        renderHome(displayedConnectionStage, message)
    }

    private fun hideConnectionStatus() {
        showHome()
        displayedConnectionStage = CrvConnectionStage.IDLE
        lastProgressStep = 0
        renderHome(displayedConnectionStage, "Waiting for iPhone USB")
    }

    private fun isConnectionError(message: String): Boolean {
        return isCrvConnectionError(message)
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

    private fun showHome() {
        if (destroyed || !::home.isInitialized) return
        homeVisible = true
        runOnUiThread { if (!destroyed) home.visibility = View.VISIBLE }
    }

    private fun showVideo() {
        if (destroyed || !::home.isInitialized) return
        homeVisible = false
        runOnUiThread { if (!destroyed) home.visibility = View.GONE }
    }

    private fun shouldReturnHome(message: String): Boolean {
        val value = message.lowercase(java.util.Locale.US)
        return "session ended" in value ||
            "carplay stopped" in value ||
            "carplay failed" in value ||
            "transport error" in value ||
            "disconnected" in value
    }

    private fun renderHome(stage: CrvConnectionStage, message: String) {
        if (destroyed || !::heroTitle.isInitialized || !::stageCard.isInitialized) return
        val copy = homeCopyFor(stage, message)
        val isError = stage == CrvConnectionStage.ERROR

        runOnUiThread {
            if (destroyed) return@runOnUiThread
            val progressStep = progressStepFor(stage)
            if (!isError && stage != CrvConnectionStage.RETRYING) {
                lastProgressStep = progressStep
            }
            val activeStep = if (isError || stage == CrvConnectionStage.RETRYING) {
                lastProgressStep
            } else {
                progressStep
            }.coerceIn(0, PROGRESS_STEPS.lastIndex)

            heroTitle.text = copy.first
            heroTitle.setTextColor(if (isError) ERROR_TITLE_COLOR else Color.WHITE)
            heroBody.text = copy.second
            heroBody.setTextColor(if (isError) ERROR_BODY_COLOR else HOME_BODY_COLOR)
            stageLabel.text = when (stage) {
                CrvConnectionStage.ERROR -> "Stopped at ${PROGRESS_STEPS[activeStep]}"
                CrvConnectionStage.RETRYING -> "Trying again"
                CrvConnectionStage.CARPLAY_ACTIVE -> "Connected"
                else -> "Step ${activeStep + 1} of ${PROGRESS_STEPS.size} · ${PROGRESS_STEPS[activeStep]}"
            }
            stageLabel.setTextColor(if (isError) ERROR_TITLE_COLOR else HOME_MUTED_COLOR)
            stageCard.background = cardBackground(
                if (isError) ERROR_CARD_COLOR else CARD_COLOR,
                if (isError) ERROR_BORDER_COLOR else CARD_STROKE_COLOR,
            )

            val hasSession = controller != null || pendingUsbSession != null ||
                pendingDevice != null || awaitingCarPlayReattach || openCarPlayAfterPermission
            val connecting = stage != CrvConnectionStage.IDLE &&
                stage != CrvConnectionStage.ERROR &&
                stage != CrvConnectionStage.CARPLAY_ACTIVE
            connectButton.text = when (stage) {
                CrvConnectionStage.ERROR -> "Try again"
                CrvConnectionStage.CARPLAY_ACTIVE -> "Connected"
                CrvConnectionStage.IDLE -> "Connect iPhone"
                else -> "Connecting…"
            }
            connectButton.isEnabled =
                (stage == CrvConnectionStage.IDLE || isError) && !hasSession
            connectButton.alpha = if (connectButton.isEnabled) 1f else 0.55f
            disconnectButton.text = if (stage == CrvConnectionStage.CARPLAY_ACTIVE) {
                "Disconnect"
            } else {
                "Cancel"
            }
            disconnectButton.isEnabled = hasSession || connecting
            disconnectButton.alpha = if (disconnectButton.isEnabled) 1f else 0.4f

            progressDots.forEachIndexed { index, step ->
                val done = index < activeStep || stage == CrvConnectionStage.CARPLAY_ACTIVE
                val current = index == activeStep && !done
                step.background = cardBackground(
                    when {
                        isError && current -> ERROR_CARD_COLOR
                        done -> PROGRESS_DONE_COLOR
                        current -> PROGRESS_CURRENT_COLOR
                        else -> HOME_BACKGROUND_COLOR
                    },
                    when {
                        isError && current -> ERROR_BORDER_COLOR
                        done || current -> DOT_ON_COLOR
                        else -> CARD_STROKE_COLOR
                    },
                )
                step.setTextColor(
                    when {
                        isError && current -> ERROR_DOT_COLOR
                        done || current -> Color.WHITE
                        else -> HOME_MUTED_COLOR
                    },
                )
            }
        }
    }

    private fun homeCopyFor(stage: CrvConnectionStage, message: String): Pair<String, String> =
        when (stage) {
            CrvConnectionStage.IDLE ->
                HOME_READY_TITLE to if (connectionMode == CrvConnectionMode.WIFI_HANDOFF) {
                    "Connect iPhone by USB. Wi-Fi takes over after CarPlay starts."
                } else {
                    HOME_READY_BODY
                }
            CrvConnectionStage.USB_DETECTED ->
                "iPhone detected" to "Keep the cable connected while CarPlay starts."
            CrvConnectionStage.USB_PERMISSION ->
                "Allow USB access" to "Approve the permission request on the car screen."
            CrvConnectionStage.USB_REENUMERATION ->
                "Preparing iPhone" to "The USB connection may briefly reconnect. Keep the cable in place."
            CrvConnectionStage.USBMUX_READY ->
                "Opening phone connection" to "Preparing the CarPlay data channel…"
            CrvConnectionStage.LOCKDOWN ->
                "Pairing with iPhone" to "Approve Trust or CarPlay on your iPhone if prompted."
            CrvConnectionStage.IAP2,
            CrvConnectionStage.MFI ->
                "Preparing CarPlay" to "Checking the accessory connection…"
            CrvConnectionStage.NCM,
            CrvConnectionStage.NETWORK_READY ->
                "Starting connection" to "Preparing the CarPlay network…"
            CrvConnectionStage.WIFI_HOTSPOT,
            CrvConnectionStage.WIFI_HANDOFF ->
                "Starting Wi-Fi" to "Keep USB connected until CarPlay appears, then unplug."
            CrvConnectionStage.AIRPLAY_LISTENING,
            CrvConnectionStage.AIRPLAY_CONNECTED ->
                "Connecting display" to if (connectionMode == CrvConnectionMode.WIFI_HANDOFF) {
                    "Your iPhone is joining Wi-Fi. Keep USB connected for now."
                } else {
                    "Your iPhone is starting the display."
                }
            CrvConnectionStage.CARPLAY_ACTIVE ->
                "CarPlay is ready" to if (connectionMode == CrvConnectionMode.WIFI_HANDOFF) {
                    "You can unplug USB and continue over Wi-Fi."
                } else {
                    "Your iPhone is on the display."
                }
            CrvConnectionStage.RETRYING ->
                "Reconnecting" to "Trying to restore the connection automatically…"
            CrvConnectionStage.ERROR ->
                "Connection needs attention" to friendlyError(message)
        }

    private fun friendlyError(message: String): String {
        val value = message.lowercase(java.util.Locale.US)
        return when {
            "mfi" in value || "authentication" in value ->
                "CarPlay authentication is unavailable. Check the test build and provisioned identity."
            "hotspot" in value || "wi-fi" in value ->
                "The Wi-Fi handoff did not start. Check the car's Wi-Fi settings, then try again."
            "permission" in value || "denied" in value ->
                "Allow the requested USB or network permission, then try again."
            "not detected" in value || "disconnected" in value ->
                "Check the USB data cable and reconnect your iPhone."
            "timed out" in value || "timeout" in value ->
                "The connection took too long. Reconnect your iPhone and try again."
            else ->
                "Reconnect your iPhone and try again. Details are saved in the diagnostic file."
        }
    }

    private fun progressStepFor(stage: CrvConnectionStage): Int = when (stage) {
        CrvConnectionStage.IDLE,
        CrvConnectionStage.USB_DETECTED,
        CrvConnectionStage.USB_PERMISSION,
        CrvConnectionStage.USB_REENUMERATION,
        CrvConnectionStage.USBMUX_READY -> 0
        CrvConnectionStage.LOCKDOWN,
        CrvConnectionStage.IAP2,
        CrvConnectionStage.MFI -> 1
        CrvConnectionStage.NCM,
        CrvConnectionStage.NETWORK_READY,
        CrvConnectionStage.WIFI_HOTSPOT,
        CrvConnectionStage.WIFI_HANDOFF -> 2
        CrvConnectionStage.AIRPLAY_LISTENING,
        CrvConnectionStage.AIRPLAY_CONNECTED,
        CrvConnectionStage.CARPLAY_ACTIVE -> 3
        CrvConnectionStage.RETRYING,
        CrvConnectionStage.ERROR -> lastProgressStep
    }

    private fun beginManualConnect() {
        if (destroyed) return
        manualDisconnect = false
        reconnectAttempts = 0
        reconnectBlockedForMfi = false
        reconnectGeneration++
        val device = usbHost.discover().firstOrNull()
        if (device == null) {
            connectionError("iPhone not detected; connect the USB cable")
            return
        }
        beginConnectionStatus("Connecting to iPhone")
        if (hasActiveCarPlayLayout(device)) {
            awaitingCarPlayReattach = false
            requestPermissionForCarPlay(device)
        } else {
            awaitingCarPlayReattach = false
            requestPermission(device)
        }
    }

    private fun beginManualDisconnect() {
        if (destroyed) return
        manualDisconnect = true
        reconnectGeneration++
        usbTransitionGeneration.incrementAndGet()
        awaitingCarPlayReattach = false
        openCarPlayAfterPermission = false
        reconnectAttempts = 0
        val active = controller
        controller = null
        runCatching { active?.close() }
        runCatching { pendingUsbSession?.close() }
        pendingUsbSession = null
        pendingDevice = null
        showHome()
        displayedConnectionStage = CrvConnectionStage.IDLE
        renderHome(displayedConnectionStage, "Disconnected")
    }

    private fun cardBackground(fill: Int, stroke: Int): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(14).toFloat()
            setColor(fill)
            if (stroke != 0) setStroke(1, stroke)
        }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).roundToInt()

    override fun onSurfaceTextureAvailable(texture: SurfaceTexture, width: Int, height: Int) {
        videoSurface?.release()
        // Keep the decoder/native buffer at the CarPlay protocol size. TextureView can scale the
        // composed image to the physical Honda panel without making MediaCodec renegotiate buffers.
        runCatching {
            texture.setDefaultBufferSize(
                Crv2021Config.CARPLAY_WIDTH,
                Crv2021Config.CARPLAY_HEIGHT,
            )
        }
        videoSurface = Surface(texture)
        surfaceWidth = Crv2021Config.CARPLAY_WIDTH
        surfaceHeight = Crv2021Config.CARPLAY_HEIGHT
        controller?.updateSurface(videoSurface)
        maybeStartCarPlay()
        if (!manualDisconnect && controller == null && pendingUsbSession == null) {
            reconnectAttempts = 0
            reconnectGeneration++
            val device = usbHost.discover().firstOrNull()
            if (device != null) {
                beginConnectionStatus("Display ready; reconnecting CarPlay")
                if (hasActiveCarPlayLayout(device)) {
                    awaitingCarPlayReattach = false
                    requestPermissionForCarPlay(device)
                } else {
                    awaitingCarPlayReattach = false
                    requestPermission(device)
                }
            }
        }
    }

    override fun onSurfaceTextureSizeChanged(
        texture: SurfaceTexture,
        width: Int,
        height: Int,
    ) {
        // View size is presentation-only. Keep AirPlay and MediaCodec fixed at 1280x720 so a
        // layout resize cannot trigger a different encoded stream or decoder buffer geometry.
        runCatching {
            texture.setDefaultBufferSize(
                Crv2021Config.CARPLAY_WIDTH,
                Crv2021Config.CARPLAY_HEIGHT,
            )
        }
        surfaceWidth = Crv2021Config.CARPLAY_WIDTH
        surfaceHeight = Crv2021Config.CARPLAY_HEIGHT
    }

    override fun onSurfaceTextureUpdated(texture: SurfaceTexture) = Unit

    override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean {
        // API17 cannot retarget an existing MediaCodec, so detach/recreate only the decoder.
        // Keep USB/NCM/Lockdown/iAP2/AirPlay alive across ordinary TextureView recreation.
        val oldSurface = videoSurface
        videoSurface = null
        controller?.updateSurface(null)
        oldSurface?.release()
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

        videoSurface?.release()
        videoSurface = null

        mediaCoreMonitor?.close()
        mediaCoreMonitor = null

        var diagnosticsCloseQueued = false
        if (::diagnostics.isInitialized) {
            diagnostics.log("app stopped")
            if (::runtimeSnapshotProbe.isInitialized && !diagnosticIo.isShutdown) {
                diagnosticsCloseQueued = runCatching {
                    diagnosticIo.execute {
                        try {
                            runtimeSnapshotProbe.collect("app-stop").forEach(diagnostics::log)
                        } finally {
                            diagnostics.close()
                        }
                    }
                    true
                }.getOrDefault(false)
            }
            if (!diagnosticsCloseQueued) diagnostics.close()
        }
        // Let an already queued final diagnostic snapshot finish without blocking the UI thread.
        diagnosticIo.shutdown()
        io.shutdownNow()
        super.onDestroy()
    }

    companion object {
        private const val REQUEST_VPN = 1001
        private const val STATUS_HIDE_AFTER_ACTIVE_MILLIS = 2_000L
        private const val USB_REENUMERATION_TIMEOUT_MILLIS = 15_000L
        private const val USB_REENUMERATION_POLL_MILLIS = 500L
        private val RECONNECT_DELAYS_MILLIS = longArrayOf(1_000L, 2_000L, 4_000L, 8_000L)
        private const val MAX_AUTOMATIC_RECONNECTS = 4
        private val HOME_BACKGROUND_COLOR = 0xFF0B1016.toInt()
        private val HOME_BODY_COLOR = 0xFFB9C4D0.toInt()
        private val HOME_MUTED_COLOR = 0xFF8A97A6.toInt()
        private val CARD_COLOR = 0xFF16202B.toInt()
        private val CARD_STROKE_COLOR = 0xFF2A3A4A.toInt()
        private val PRIMARY_BUTTON_COLOR = 0xFF2F6FED.toInt()
        private val DOT_ON_COLOR = 0xFF4C9AFF.toInt()
        private val PROGRESS_DONE_COLOR = 0xFF174E83.toInt()
        private val PROGRESS_CURRENT_COLOR = 0xFF203B5D.toInt()
        private val SECONDARY_BUTTON_STROKE_COLOR = 0xFF3A4553.toInt()
        private val ERROR_TITLE_COLOR = 0xFFF09595.toInt()
        private val ERROR_BODY_COLOR = 0xFFE0B0B0.toInt()
        private val ERROR_BORDER_COLOR = 0xFFA32D2D.toInt()
        private val ERROR_CARD_COLOR = 0xFF2A1616.toInt()
        private val ERROR_DOT_COLOR = 0xFFE24B4A.toInt()
        private const val HOME_READY_TITLE = "Ready when you are"
        private const val HOME_READY_BODY =
            "Plug your iPhone into the USB data port. Allow CarPlay when your iPhone asks."
        private val PROGRESS_STEPS =
            listOf("Phone", "Pair", "Network", "Display")
    }
}
