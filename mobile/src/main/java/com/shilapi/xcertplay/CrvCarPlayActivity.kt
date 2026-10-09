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
import java.util.ArrayDeque
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
    private lateinit var status: TextView
    private lateinit var logScroll: ScrollView
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
    private val statusLines = ArrayDeque<String>()
    private var statusSequence = 0
    private var lastStatusMessage: String? = null
    private var displayedConnectionStage = CrvConnectionStage.IDLE

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
            text = "Mode: USB"
            setTextColor(Color.WHITE)
            setBackgroundColor(0x33000000)
            setPadding(dp(14), dp(8), dp(14), dp(8))
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
                reconnectBlockedForMfi = false
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
            text = "Stage: idle"
        }

        progressBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        PROGRESS_STEPS.forEach { _ ->
            val dot = TextView(this).apply {
                text = "○"
                setTextColor(DOT_OFF_COLOR)
                textSize = 15f
                setPadding(0, 0, dp(9), 0)
            }
            progressDots.add(dot)
            progressBar.addView(dot)
        }

        connectButton = TextView(this).apply {
            text = "Connect phone"
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

        status = TextView(this).apply {
            text = ""
            visibility = View.VISIBLE
            setTextColor(HOME_BODY_COLOR)
            setBackgroundColor(0x22000000)
            gravity = Gravity.LEFT or Gravity.TOP
            typeface = Typeface.MONOSPACE
            textSize = 12f
            setSingleLine(false)
            maxLines = STATUS_MAX_LINES
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }

        logScroll = ScrollView(this).apply {
            addView(status, ViewGroup.LayoutParams(-1, -2))
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
            addView(
                TextView(this@CrvCarPlayActivity).apply {
                    text = "Activity log"
                    setTextColor(HOME_MUTED_COLOR)
                    textSize = 13f
                },
                LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(20) },
            )
            addView(
                logScroll,
                LinearLayout.LayoutParams(-1, dp(140)).apply { topMargin = dp(6) },
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
            CrvSystemInfoProbe(probeContext).collect().forEach(diagnostics::log)
            CrvHondaPlatformProbe(probeContext).collect().forEach(diagnostics::log)
            runtimeSnapshotProbe.collect("app-start").forEach(diagnostics::log)
        }
        appendStatusLine("App started (Android API " + android.os.Build.VERSION.SDK_INT + ")")
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
        showHome()
        connectionStatus(message)
    }

    private fun connectionStatus(message: String) {
        if (!homeVisible) return
        appendStatusLine(message)
    }

    private fun connectionError(message: String) {
        diagnostics.log(CrvConnectionStage.ERROR, message)
        showHome()
        appendStatusLine("ERROR: $message")
        displayedConnectionStage = CrvConnectionStage.ERROR
        renderHome(displayedConnectionStage, message)
    }

    private fun hideConnectionStatus() {
        showHome()
        appendStatusLine("Waiting for iPhone USB")
        displayedConnectionStage = CrvConnectionStage.IDLE
        renderHome(displayedConnectionStage, "Waiting for iPhone USB")
    }

    private fun appendStatusLine(message: String) {
        runOnUiThread {
            if (destroyed) return@runOnUiThread
            if (message == lastStatusMessage) return@runOnUiThread
            lastStatusMessage = message
            statusSequence += 1
            val line = String.format(java.util.Locale.US, "%02d  %s", statusSequence, message)
            statusLines.addLast(line)
            while (statusLines.size > STATUS_MAX_LINES) {
                statusLines.removeFirst()
            }
            val builder = StringBuilder()
            val iterator = statusLines.iterator()
            while (iterator.hasNext()) {
                if (builder.isNotEmpty()) builder.append('\n')
                builder.append(iterator.next())
            }
            status.text = builder.toString()
            status.visibility = View.VISIBLE
            if (::logScroll.isInitialized) {
                logScroll.post { logScroll.fullScroll(View.FOCUS_DOWN) }
            }
        }
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
        val active = stageIndex(stage)
        val isError = stage == CrvConnectionStage.ERROR
        runOnUiThread {
            if (destroyed) return@runOnUiThread
            heroTitle.text = copy.first
            heroTitle.setTextColor(if (isError) ERROR_TITLE_COLOR else Color.WHITE)
            heroBody.text = copy.second
            heroBody.setTextColor(if (isError) ERROR_BODY_COLOR else HOME_BODY_COLOR)
            stageLabel.text = "Stage: " + stageName(stage)
            stageLabel.setTextColor(if (isError) ERROR_TITLE_COLOR else HOME_MUTED_COLOR)
            stageCard.background = cardBackground(
                if (isError) ERROR_CARD_COLOR else CARD_COLOR,
                if (isError) ERROR_BORDER_COLOR else CARD_STROKE_COLOR,
            )
            val connected = stage == CrvConnectionStage.CARPLAY_ACTIVE
            connectButton.text = if (isError) "Retry connection" else "Connect phone"
            connectButton.isEnabled = !connected
            val hasSession =
                controller != null || pendingUsbSession != null || pendingDevice != null
            disconnectButton.isEnabled = hasSession
            disconnectButton.alpha = if (hasSession) 1f else 0.4f
            progressDots.forEachIndexed { index, dot ->
                val on = if (isError) true else active >= 0 && index <= active
                dot.text = if (on) "●" else "○"
                dot.setTextColor(
                    when {
                        isError -> ERROR_DOT_COLOR
                        on -> DOT_ON_COLOR
                        else -> DOT_OFF_COLOR
                    },
                )
            }
        }
    }

    private fun homeCopyFor(stage: CrvConnectionStage, message: String): Pair<String, String> =
        when (stage) {
            CrvConnectionStage.IDLE,
            CrvConnectionStage.USB_DETECTED,
            CrvConnectionStage.USB_PERMISSION,
            CrvConnectionStage.USB_REENUMERATION ->
                HOME_READY_TITLE to HOME_READY_BODY
            CrvConnectionStage.USBMUX_READY ->
                "iPhone detected" to "Opening the CarPlay data channel…"
            CrvConnectionStage.LOCKDOWN ->
                "Pairing with iPhone" to "Verifying the saved pairing…"
            CrvConnectionStage.IAP2 ->
                "CarPlay channel ready" to "Starting iAP2 identification…"
            CrvConnectionStage.MFI ->
                "Authenticating" to "Negotiating the MFi identity…"
            CrvConnectionStage.NCM,
            CrvConnectionStage.NETWORK_READY,
            CrvConnectionStage.WIFI_HOTSPOT,
            CrvConnectionStage.WIFI_HANDOFF ->
                "Network ready" to "Bringing up the CarPlay network link…"
            CrvConnectionStage.AIRPLAY_LISTENING ->
                "Waiting for AirPlay" to "The iPhone is connecting to the display…"
            CrvConnectionStage.AIRPLAY_CONNECTED ->
                "Starting display" to "Negotiating the video stream…"
            CrvConnectionStage.CARPLAY_ACTIVE ->
                "CarPlay active" to "Your iPhone is on the display."
            CrvConnectionStage.RETRYING ->
                "Retrying" to "Reconnecting to your iPhone…"
            CrvConnectionStage.ERROR ->
                "Connection problem" to message
        }

    private fun stageIndex(stage: CrvConnectionStage): Int = when (stage) {
        CrvConnectionStage.IDLE,
        CrvConnectionStage.USB_DETECTED,
        CrvConnectionStage.USB_PERMISSION,
        CrvConnectionStage.USB_REENUMERATION -> 0
        CrvConnectionStage.USBMUX_READY -> 1
        CrvConnectionStage.LOCKDOWN -> 2
        CrvConnectionStage.IAP2 -> 3
        CrvConnectionStage.MFI -> 4
        CrvConnectionStage.NCM,
        CrvConnectionStage.NETWORK_READY,
        CrvConnectionStage.WIFI_HOTSPOT,
        CrvConnectionStage.WIFI_HANDOFF -> 5
        CrvConnectionStage.AIRPLAY_LISTENING,
        CrvConnectionStage.AIRPLAY_CONNECTED -> 6
        CrvConnectionStage.CARPLAY_ACTIVE -> 7
        CrvConnectionStage.RETRYING,
        CrvConnectionStage.ERROR -> -1
    }

    private fun stageName(stage: CrvConnectionStage): String = when (stage) {
        CrvConnectionStage.IDLE -> "idle"
        CrvConnectionStage.USB_DETECTED -> "iPhone detected"
        CrvConnectionStage.USB_PERMISSION -> "USB permission"
        CrvConnectionStage.USB_REENUMERATION -> "USB mode switch"
        CrvConnectionStage.USBMUX_READY -> "USBMUX ready"
        CrvConnectionStage.NETWORK_READY -> "network ready"
        CrvConnectionStage.LOCKDOWN -> "lockdown"
        CrvConnectionStage.IAP2 -> "iAP2"
        CrvConnectionStage.MFI -> "MFi"
        CrvConnectionStage.NCM -> "CDC-NCM"
        CrvConnectionStage.AIRPLAY_LISTENING -> "AirPlay listening"
        CrvConnectionStage.AIRPLAY_CONNECTED -> "AirPlay connected"
        CrvConnectionStage.WIFI_HOTSPOT -> "Wi-Fi hotspot"
        CrvConnectionStage.WIFI_HANDOFF -> "Wi-Fi handoff"
        CrvConnectionStage.CARPLAY_ACTIVE -> "CarPlay active"
        CrvConnectionStage.RETRYING -> "retrying"
        CrvConnectionStage.ERROR -> "error"
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
        appendStatusLine("Disconnected")
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
        videoSurface = Surface(texture)
        surfaceWidth = width.coerceAtLeast(1)
        surfaceHeight = height.coerceAtLeast(1)
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
        surfaceWidth = width.coerceAtLeast(1)
        surfaceHeight = height.coerceAtLeast(1)
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

        if (::diagnostics.isInitialized) {
            diagnostics.log("app stopped")
            if (::runtimeSnapshotProbe.isInitialized && !diagnosticIo.isShutdown) {
                runCatching {
                    diagnosticIo.execute {
                        runtimeSnapshotProbe.collect("app-stop").forEach(diagnostics::log)
                    }
                }
            }
        }
        // Let an already queued final diagnostic snapshot finish without blocking the UI thread.
        diagnosticIo.shutdown()
        io.shutdownNow()
        super.onDestroy()
    }

    companion object {
        private const val REQUEST_VPN = 1001
        private const val STATUS_MAX_LINES = 16
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
        private val DOT_OFF_COLOR = 0xFF3A4553.toInt()
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
            listOf("USB", "Mux", "Pair", "iAP2", "MFi", "Net", "AirPlay", "Active")
    }
}
