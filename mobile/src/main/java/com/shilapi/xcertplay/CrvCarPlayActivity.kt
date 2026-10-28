package com.shilapi.xcertplay

import android.app.Activity
import android.app.AlertDialog
import android.view.KeyEvent
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
    private var connectionMode = CrvConnectionMode.AUTO
    @Volatile private var homeVisible = true
    @Volatile private var manualDisconnect = false
    private val touchSlots = CrvTouchSlotState()
    @Volatile private var displayedConnectionStage = CrvConnectionStage.IDLE
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
    private val reconnectBudget = automaticAttemptBudget
    private var automaticConnectBlocked = false
    private var vpnConsentPending = false
    private var usbOpenInFlight = false
    private var reconnectOnSurfaceReady = false
    private var firstFrameCheckGeneration = 0
    private val presentation = CrvConnectionPresentation()
    private var reconnectBlockedForMfi = false
    @Volatile private var destroyed = false
    private val connectionEpoch = CrvConnectionEpoch()
    private var controllerStarts = 0L
    private val sessionStability = CrvSessionStability()
    private var lastReconnectFailure = CrvReconnectFailure.TRANSIENT
    private val cleanupGate = CrvCleanupGate()
    private var pendingDevice: UsbDevice? = null
    private var pendingUsbSession: Iap2UsbSession? = null

    private var videoSurface: Surface? = null
    private var videoSurfaceOwner: CrvWiredCarPlayController? = null
    private var surfaceWidth = Crv2021Config.CARPLAY_WIDTH
    private var surfaceHeight = Crv2021Config.CARPLAY_HEIGHT

    private var controller: CrvWiredCarPlayController? = null
    private var activityResumed = false
    private var mediaCoreMonitor: CrvHondaMediaCoreMonitor? = null
    private var callRouteMonitor: CrvCallRouteMonitor? = null
    private lateinit var runtimeSnapshotProbe: CrvRuntimeSnapshotProbe
    private val snapshotReasons = HashSet<String>()
    // Only accessed on diagnosticIo; connection events themselves are always retained.
    private var lastRoutineSnapshotNs = 0L

    override fun onResume() {
        super.onResume()
        activityResumed = true
        controller?.setVideoVisible(true)
        if (presentation.retainsVideoPage()) showVideo()
        callRouteMonitor?.let { monitor ->
            if (!diagnosticIo.isShutdown) diagnosticIo.execute { monitor.foreground("resume") }
        }
        if (::diagnostics.isInitialized) {
            diagnostics.log("CarPlay activity resumed stage=$displayedConnectionStage")
        }
    }

    override fun onPause() {
        activityResumed = false
        controller?.setVideoVisible(false)
        mainHandler.postDelayed({
            if (!destroyed && !activityResumed && !diagnosticIo.isShutdown) {
                diagnosticIo.execute { callRouteMonitor?.foreground("pause") }
            }
        }, 400L)
        if (::diagnostics.isInitialized) {
            diagnostics.log("CarPlay activity paused stage=$displayedConnectionStage")
        }
        super.onPause()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (CrvMediaButtonReceiver.dispatch(event)) return true
        if (event.action == KeyEvent.ACTION_DOWN && ::diagnostics.isInitialized) {
            diagnostics.log("Hardware key keyCode=${event.keyCode} scanCode=${event.scanCode} repeat=${event.repeatCount}")
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        val profile = CrvVideoProfile.fromStored(getSharedPreferences("crv_video", Context.MODE_PRIVATE)
            .getString("profile", null))
        surfaceWidth = profile.width
        surfaceHeight = profile.height

        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        video = TextureView(this).apply {
            surfaceTextureListener = this@CrvCarPlayActivity
            isOpaque = true
            isClickable = true
            setOnTouchListener { view, event ->
                handleCarPlayTouch(view, event)
            }
        }

        modeButton = TextView(this).apply {
            text = "Auto: USB first"
            setTextColor(Color.WHITE)
            textSize = 14f
            gravity = Gravity.CENTER
            background = cardBackground(CARD_COLOR, CARD_STROKE_COLOR)
            setPadding(dp(18), dp(12), dp(18), dp(12))
            isFocusable = true
            setOnClickListener {
                connectionMode = when (connectionMode) {
                    CrvConnectionMode.AUTO -> CrvConnectionMode.WIFI_HANDOFF
                    CrvConnectionMode.WIFI_HANDOFF -> CrvConnectionMode.WIRED
                    CrvConnectionMode.WIRED -> CrvConnectionMode.AUTO
                }
                text = when (connectionMode) {
                    CrvConnectionMode.AUTO -> "Auto: USB first"
                    CrvConnectionMode.WIFI_HANDOFF -> "Wi-Fi test mode"
                    CrvConnectionMode.WIRED -> "USB connection"
                }
                diagnostics.log("Connection mode changed to $connectionMode; closing previous controller")
                retireConnection(CrvRecoveryTrigger.MODE_CHANGE)
                reconnectBudget.reset()
                automaticConnectBlocked = false
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

        val navigationVolume = TextView(this).apply {
            text = "导航音量"
            setTextColor(Color.WHITE)
            textSize = 15f
            setPadding(dp(18), dp(12), dp(18), dp(12))
            isFocusable = true
            setOnClickListener {
                val settings = getSharedPreferences("crv_audio", Context.MODE_PRIVATE)
                val values = intArrayOf(100, 150, 200, 300)
                AlertDialog.Builder(this@CrvCarPlayActivity)
                    .setTitle("导航播报音量")
                    .setSingleChoiceItems(arrayOf("原始音量", "增强 1.5 倍", "增强 2 倍", "增强 3 倍"),
                        values.indexOf(settings.getInt("navigation_gain_percent", 150))) { dialog, index ->
                        settings.edit().putInt("navigation_gain_percent", values[index]).apply()
                        dialog.dismiss()
                    }.setNegativeButton("取消", null).show()
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
            addView(navigationVolume, LinearLayout.LayoutParams(-2, -2))
            addView(TextView(this@CrvCarPlayActivity).apply {
                text = "诊断日志"
                setTextColor(Color.WHITE)
                setPadding(dp(12), dp(12), dp(12), dp(12))
                setOnClickListener {
                    AlertDialog.Builder(this@CrvCarPlayActivity).setTitle("诊断日志")
                        .setSingleChoiceItems(arrayOf("普通（保留关键事件和性能摘要）", "详细（故障排查）"),
                            if (diagnostics.detailed) 1 else 0) { dialog, index ->
                            diagnostics.setDetailed(this@CrvCarPlayActivity, index == 1)
                            dialog.dismiss()
                        }.setNeutralButton("画面性能") { _, _ ->
                            val settings = getSharedPreferences("crv_video", Context.MODE_PRIVATE)
                            val selected = CrvVideoProfile.fromStored(settings.getString("profile", null))
                            AlertDialog.Builder(this@CrvCarPlayActivity).setTitle("画面性能")
                                .setMessage("下次启动应用生效。低负载模式降低清晰度；默认保持标准模式。")
                                .setPositiveButton("标准模式") { _, _ -> settings.edit().putString("profile", CrvVideoProfile.STANDARD.name).apply() }
                                .setNeutralButton("低负载模式") { _, _ -> settings.edit().putString("profile", CrvVideoProfile.LOW_LOAD.name).apply() }
                                .setNegativeButton("取消", null).show()
                            diagnostics.log("Video profile settings current=${selected.name}; changes apply on next Activity")
                        }.setNegativeButton("取消", null).show()
                }
            }, LinearLayout.LayoutParams(-2, -2))
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
        com.shilapi.xcertplay.airplay.MediaCryptoDiagnostics.configure(intent.getStringExtra("crypto_sampling"))
        diagnostics.log("Runtime media sampling mode=${com.shilapi.xcertplay.airplay.MediaCryptoDiagnostics.sampling}")
        callRouteMonitor = CrvCallRouteMonitor(this, diagnostics::log).also { it.start() }
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
            if (controller?.isAutoWirelessAfterUsbDetach() == true) {
                runOnUiThread {
                    retireConnection(CrvRecoveryTrigger.USB_REATTACHED)
                    manualDisconnect = false
                    reconnectGeneration++
                    beginConnectionStatus("USB reattached; restoring wired CarPlay")
                    requestPermission(device)
                }
                return@registerAttachReceiver
            }
            if (controller?.shouldKeepWirelessOnUsbDetach() == true) {
                reportStatus("USB attached; continuing Wi-Fi CarPlay")
                return@registerAttachReceiver
            }
            if (automaticConnectBlocked && lastReconnectFailure == CrvReconnectFailure.USB_MISSING) {
                automaticConnectBlocked = false
            }
            if (manualDisconnect || automaticConnectBlocked) return@registerAttachReceiver
            if (controller != null || usbOpenInFlight || pendingUsbSession != null) {
                diagnostics.log("USB attach ignored: connection already owned or opening")
                return@registerAttachReceiver
            }
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
        detachReceiver = usbHost.registerDetachReceiver { detached ->
            if (!awaitingCarPlayReattach) {
                runOnUiThread {
                    val owner = controller
                    val pending = pendingDevice
                    val detachedIdentity = usbIdentity(detached)
                    val activeIdentity = owner?.wiredUsbIdentity ?: pending?.let(::usbIdentity)
                    diagnostics.log("USB detach broadcast device=${detached.deviceName} id=${detached.deviceId} " +
                        "active=${activeIdentity?.name} stage=$displayedConnectionStage")
                    // Allow the legacy UsbManager device list to settle. Never let a delayed
                    // event close a replacement controller or an unrelated Apple device.
                    mainHandler.postDelayed({
                        if (destroyed || awaitingCarPlayReattach || controller !== owner || pendingDevice !== pending) {
                            return@postDelayed
                        }
                        val present = runCatching { usbManager.deviceList.values.map(::usbIdentity) }.getOrNull()
                        if (!shouldHandleCrvUsbDetach(detachedIdentity, activeIdentity, present)) {
                            diagnostics.log("USB detach ignored: unrelated device or active enumeration still present")
                            return@postDelayed
                        }
                        diagnostics.log("USB detach accepted inventory=${if (present == null) "unavailable" else "active device absent"}; closing active USB path")
                        usbTransitionGeneration.incrementAndGet()
                        if (controller?.shouldKeepWirelessOnUsbDetach() == true) {
                            controller?.onUsbDetached()
                            reportStatus("USB removed; keeping Wi-Fi handoff")
                            return@postDelayed
                        }
                        retireConnection(CrvRecoveryTrigger.USB_DETACHED)
                        openCarPlayAfterPermission = false
                        reconnectGeneration++
                        presentation.clear()
                        connectionError("iPhone disconnected")
                    }, 300L)
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

    private fun usbIdentity(device: UsbDevice) =
        CrvUsbIdentity(device.deviceName, device.deviceId, device.vendorId, device.productId)

    private fun prepareVpn() {
        if (vpnConsentPending) return
        val consent = CarPlayVpnService.prepare(this)
        if (consent == null) {
            connectionStatus("CarPlay network permission ready")
        } else {
            vpnConsentPending = true
            connectionStatus("Requesting CarPlay network permission")
            startActivityForResult(consent, REQUEST_VPN)
        }
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_VPN) return
        vpnConsentPending = false
        diagnostics.log("VPN consent result=$resultCode ready=${CarPlayVpnService.prepare(this) == null}")
        if (resultCode == RESULT_OK && CarPlayVpnService.prepare(this) == null) {
            automaticConnectBlocked = false
            connectionStatus("CarPlay network permission granted")
            maybeStartCarPlay()
        } else {
            automaticConnectBlocked = true
            reconnectGeneration++
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
        if (deferUntilCleanup { requestPermission(device) }) return
        if (automaticConnectBlocked || controller != null || usbOpenInFlight || pendingUsbSession != null) return
        openCarPlayAfterPermission = false
        when (usbHost.requestPermission(device)) {
            is IphoneUsbHost.PermissionRequest.AlreadyGranted -> routeGrantedDevice(device)
            is IphoneUsbHost.PermissionRequest.Requested ->
                connectionStatus("Waiting for USB permission")
        }
    }

    private fun routeGrantedDevice(device: UsbDevice) {
        if (deferUntilCleanup { routeGrantedDevice(device) }) return
        if (automaticConnectBlocked || controller != null || usbOpenInFlight || pendingUsbSession != null) return
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
        if (deferUntilCleanup { requestPermissionForCarPlay(device) }) return
        if (automaticConnectBlocked || controller != null || usbOpenInFlight || pendingUsbSession != null) return
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
        if (deferUntilCleanup { openCarPlayUsb(device) }) return
        if (automaticConnectBlocked || controller != null || pendingUsbSession != null || usbOpenInFlight) return
        usbOpenInFlight = true
        val generation = usbTransitionGeneration.incrementAndGet()
        connectionStatus("Opening CarPlay USB data paths")
        usbHost.openIap2UsbSessionAsync(device, io) { result ->
            // Use Activity's UI dispatch so onDestroy's timer cancellation does not
            // discard a queued result before its USB session can be closed.
            runOnUiThread {
                if (destroyed || generation != usbTransitionGeneration.get()) {
                    if (result is IphoneUsbHost.Iap2SessionResult.Connected) {
                        queueConnectionCleanup(null, result.session, CrvRecoveryTrigger.USB_REATTACHED)
                    }
                    return@runOnUiThread
                }
                usbOpenInFlight = false
                when (result) {
                    is IphoneUsbHost.Iap2SessionResult.Connected -> {
                        openCarPlayAfterPermission = false
                        awaitingCarPlayReattach = false
                        usbTransitionGeneration.incrementAndGet()
                        synchronized(this) {
                            pendingUsbSession?.let {
                                queueConnectionCleanup(null, it, CrvRecoveryTrigger.USB_REATTACHED)
                            }
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
        if (destroyed || manualDisconnect || automaticConnectBlocked || cleanupGate.pending > 0 || controller != null) return
        if (deferUntilCleanup { maybeStartCarPlay() }) return
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

        if (CarPlayVpnService.prepare(this) != null) {
            lastReconnectFailure = CrvReconnectFailure.PERMISSION_REQUIRED
            connectionStatus("Waiting for CarPlay network permission")
            return
        }
        if (!reconnectBudget.tryStart()) {
            automaticConnectBlocked = true
            retireConnection(CrvRecoveryTrigger.ACTIVE_DISCONNECT)
            connectionError("CarPlay stopped; tap Connect to retry")
            return
        }
        pendingDevice = null
        pendingUsbSession = null
        presentation.clear()

        val epoch = connectionEpoch.advance()
        diagnostics.log("attempt=$epoch controller startIndex=${++controllerStarts}")
        synchronized(snapshotReasons) { snapshotReasons.clear() }
        lastReconnectFailure = CrvReconnectFailure.TRANSIENT
        sessionStability.clear()
        lateinit var next: CrvWiredCarPlayController
        next = CrvWiredCarPlayController(
            context = this,
            usbManager = usbManager,
            surface = surface,
            displayWidth = surfaceWidth,
            displayHeight = surfaceHeight,
            report = { message -> reportStatus(message, epoch) },
            recordProtocolTrace = { message -> diagnostics.log("attempt=$epoch $message") },
            detailedProtocol = { diagnostics.detailed },
            reportFailure = { label, error ->
                diagnostics.logFailure("attempt=$epoch $label", error)
                runOnUiThread {
                    if (!destroyed && connectionEpoch.isCurrent(epoch)) {
                        lastReconnectFailure = classifyCrvReconnectFailure(error)
                    }
                }
            },
            mode = connectionMode,
            onConnectionEvent = { event, message ->
                runOnUiThread {
                    if (!destroyed && connectionEpoch.isCurrent(epoch)) {
                        handleConnectionEvent(event, message, epoch)
                    }
                }
            },
            onStopped = {
                runOnUiThread {
                    if (!destroyed && controller === next && connectionEpoch.isCurrent(epoch)) {
                        lastReconnectFailure = when (next.recoveryTrigger) {
                            CrvRecoveryTrigger.USB_DETACHED -> CrvReconnectFailure.USB_MISSING
                            CrvRecoveryTrigger.SYSTEM_VPN_REVOKED -> CrvReconnectFailure.PERMISSION_REQUIRED
                            else -> lastReconnectFailure
                        }
                        connectionEpoch.advance()
                        sessionStability.clear()
                        presentation.clear()
                        controller = null
                        scheduleReconnect()
                    }
                }
            },
        )
        controller = next
        videoSurfaceOwner = next
        next.setVideoVisible(activityResumed)
        connectionStatus(
            if (connectionMode == CrvConnectionMode.WIFI_HANDOFF) {
                "Starting Wi-Fi CarPlay handoff"
            } else {
                "Starting wired CarPlay"
            },
        )
        next.start(device, usb)
    }

    private fun reportStatus(message: String, epoch: Long? = null) {
        if (destroyed || (epoch != null && !connectionEpoch.isCurrent(epoch))) return
        if (isCrvUiDiagnostic(message)) {
            val stage = displayedConnectionStage
            if (!diagnosticIo.isShutdown) {
                runCatching {
                    diagnosticIo.execute {
                        if (!destroyed && (epoch == null || connectionEpoch.isCurrent(epoch))) {
                            diagnostics.log(stage, message)
                        }
                    }
                }
            }
            return
        }
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { reportStatus(message, epoch) }
            return
        }
        maybeCaptureRuntimeSnapshot(message, epoch)
        val stage = stageFor(message)
        val displayStage = monotonicConnectionStage(displayedConnectionStage, stage)
        if (!presentation.active) displayedConnectionStage = displayStage
        val normalized = message.lowercase(java.util.Locale.US)
        if (message == "CarPlay session ended") sessionStability.clear()
        if (
            "transport pre-auth verified" in normalized &&
            (
                "mfi identity missing" in normalized ||
                    "mfi identity invalid" in normalized
            )
        ) {
            reconnectBlockedForMfi = true
        }
        // A controller's diagnostic wording must never drive visibility or disconnects.
        if (epoch != null) {
            diagnostics.log(stage, message)
            if (!presentation.active && homeVisible) renderHome(displayStage, message)
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

    private fun handleConnectionEvent(event: CrvConnectionEvent, message: String, epoch: Long) {
        val now = android.os.SystemClock.elapsedRealtime()
        presentation.accept(event, now)
        when (event) {
            CrvConnectionEvent.ACTIVE -> {
                reconnectGeneration++
                sessionStability.active(epoch, now)
                displayedConnectionStage = CrvConnectionStage.CARPLAY_ACTIVE
                diagnostics.log("attempt=$epoch session active; waiting for rendered video")
                connectionStatus("CarPlay connected; waiting for video")
                scheduleFirstFrameCheck(epoch, 0)
            }
            CrvConnectionEvent.VIDEO_RESET -> {
                if (presentation.active) scheduleFirstFrameCheck(epoch, 0)
            }
            CrvConnectionEvent.VIDEO_RENDERED -> {
                firstFrameCheckGeneration++
                if (presentation.stable(now) && reconnectBudget.attempts != 0) {
                    reconnectBudget.reset()
                    automaticConnectBlocked = false
                    diagnostics.log("attempt=$epoch rendered playback stable; retry budget reset")
                }
            }
            CrvConnectionEvent.SESSION_ENDED -> {
                sessionStability.clear()
                connectionError(message)
            }
            CrvConnectionEvent.FAILED -> {
                sessionStability.clear()
                connectionError(message)
            }
        }
        if (presentation.shouldShowVideo()) {
            if (homeVisible) diagnostics.log("attempt=$epoch UI video visible reason=rendered-frame")
            showVideo()
        }
    }

    private fun scheduleFirstFrameCheck(epoch: Long, recoveries: Int) {
        val generation = ++firstFrameCheckGeneration
        mainHandler.postDelayed({
            if (generation != firstFrameCheckGeneration || destroyed || !connectionEpoch.isCurrent(epoch) || controller == null ||
                !presentation.active || presentation.rendered) return@postDelayed
            if (!activityResumed || videoSurface == null) {
                scheduleFirstFrameCheck(epoch, recoveries)
                return@postDelayed
            }
            diagnostics.log("attempt=$epoch first-frame timeout localRecoveries=$recoveries")
            if (recoveries < 2) {
                controller?.recoverVideo()
                scheduleFirstFrameCheck(epoch, recoveries + 1)
            } else {
                // Preserve the transport: repeated full reconnects cannot repair a decoder.
                connectionStatus("CarPlay connected; video unavailable. Tap Connect to retry")
            }
        }, 10_000L)
    }

    private fun maybeCaptureRuntimeSnapshot(message: String, epoch: Long? = null) {
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
            "carplay handshake timed out" in lower -> "handshake-timeout"
            "carplay active" in lower -> "carplay-active"
            else -> null
        } ?: return
        synchronized(snapshotReasons) {
            if (!snapshotReasons.add(reason)) return
        }
        diagnosticIo.execute {
            if (epoch != null && !connectionEpoch.isCurrent(epoch)) return@execute
            val now = System.nanoTime()
            val failure = reason.endsWith("failure") || reason == "handshake-timeout"
            if (!failure && lastRoutineSnapshotNs != 0L && now - lastRoutineSnapshotNs < 2_000_000_000L) {
                diagnostics.log("Snapshot coalesced reason=$reason attempt=$epoch")
                return@execute
            }
            if (!failure) lastRoutineSnapshotNs = now
            runtimeSnapshotProbe.collect(reason).forEach { line ->
                diagnostics.log(if (epoch == null) line else "attempt=$epoch $line")
            }
        }
    }
    private fun scheduleReconnect() {
        if (destroyed || automaticConnectBlocked) return
        if (videoSurface == null) {
            reconnectOnSurfaceReady = true
            return
        }
        reconnectOnSurfaceReady = false
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
        if (usbHost.discover().isEmpty()) {
            lastReconnectFailure = CrvReconnectFailure.USB_MISSING
        }
        val decision = crvReconnectDecision(lastReconnectFailure, (reconnectBudget.attempts - 1).coerceAtLeast(0))
        val delayMillis = decision.delayMillis
        if (delayMillis == null) {
            automaticConnectBlocked = true
            beginConnectionStatus(decision.message)
            return
        }
        val generation = ++reconnectGeneration
        // Every real controller start, including USB/Surface entry points, consumes the budget.
        diagnostics.log(
            CrvConnectionStage.RETRYING,
            "retry=${reconnectBudget.attempts} failure=$lastReconnectFailure delayMs=$delayMillis",
        )
        beginConnectionStatus(decision.message)
        displayedConnectionStage = CrvConnectionStage.RETRYING
        renderHome(displayedConnectionStage, decision.message)
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
            awaitingCarPlayReattach = false
            requestPermission(device)
        }, delayMillis)
    }

    private fun deferUntilCleanup(action: () -> Unit): Boolean {
        if (destroyed || manualDisconnect) return true
        if (cleanupGate.pending == 0 && globalCleanupPending.get() > 0) {
            // Activity recreation shares the same VPN service and physical USB device.
            cleanupGate.begin()
            cleanupIo.execute { runOnUiThread { completeCleanupOnUiThread() } }
        }
        if (!cleanupGate.defer(action)) return false
        connectionStatus("Finishing previous connection")
        return true
    }

    /** Retire ownership on the UI immediately; drain the worker and USB on a background thread. */
    private fun retireConnection(reason: CrvRecoveryTrigger) {
        val active = controller
        val usb = pendingUsbSession
        controller = null
        usbOpenInFlight = false
        firstFrameCheckGeneration++
        pendingUsbSession = null
        pendingDevice = null
        connectionEpoch.advance()
        sessionStability.clear()
        presentation.clear()
        usbTransitionGeneration.incrementAndGet()
        awaitingCarPlayReattach = false
        openCarPlayAfterPermission = false
        cleanupGate.clearDeferred()
        queueConnectionCleanup(active, usb, reason)
    }

    private fun queueConnectionCleanup(
        active: CrvWiredCarPlayController?,
        usb: Iap2UsbSession?,
        reason: CrvRecoveryTrigger,
    ) {
        if (active == null && usb == null) return
        cleanupGate.begin()
        globalCleanupPending.incrementAndGet()
        val cleanup = Runnable {
            try {
                runCatching { active?.close(reason) }
                runCatching { usb?.close() }
                var waits = 0
                while (active != null && !active.awaitClosed(1_000L)) {
                    if (++waits == 4) diagnostics.log("Controller cleanup still running; USB reconnect remains deferred")
                }
            } finally {
                globalCleanupPending.decrementAndGet()
                runOnUiThread { completeCleanupOnUiThread() }
            }
        }
        try {
            cleanupIo.execute(cleanup)
        } catch (_: java.util.concurrent.RejectedExecutionException) {
            // Always release a late USB-open result even if the cleanup queue rejects it.
            Thread(cleanup, "crv-late-usb-cleanup").apply { isDaemon = true; start() }
        }
    }

    private fun completeCleanupOnUiThread() {
        val resume = cleanupGate.complete()
        if (!destroyed && !manualDisconnect && cleanupGate.pending == 0) {
            if (resume != null) resume() else if (pendingUsbSession != null) maybeStartCarPlay()
        }
    }

    private fun beginConnectionStatus(message: String) {
        if (presentation.retainsVideoPage()) return
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
        if (presentation.retainsVideoPage()) return
        showHome()
        displayedConnectionStage = CrvConnectionStage.ERROR
        renderHome(displayedConnectionStage, message)
    }

    private fun hideConnectionStatus() {
        if (presentation.retainsVideoPage()) return
        showHome()
        displayedConnectionStage = CrvConnectionStage.IDLE
        lastProgressStep = 0
        renderHome(displayedConnectionStage, "Waiting for iPhone USB")
    }

    private fun isConnectionError(message: String): Boolean {
        return isCrvConnectionError(message)
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
        if (presentation.retainsVideoPage()) return
        if (!homeVisible) diagnostics.log("UI connection page visible stage=$displayedConnectionStage")
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
        crvRecoveryCopy(message) ?: when (stage) {
            CrvConnectionStage.IDLE ->
                HOME_READY_TITLE to if (connectionMode == CrvConnectionMode.WIFI_HANDOFF) {
                    "Connect iPhone by USB. Wi-Fi takes over after CarPlay starts."
                } else if (connectionMode == CrvConnectionMode.AUTO) {
                    "Connect iPhone by USB. Wi-Fi is prepared for cable removal when available."
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
                } else if (connectionMode == CrvConnectionMode.AUTO) {
                    "USB is active. If Wi-Fi is ready, unplugging switches to wireless."
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
        automaticConnectBlocked = false
        if (controller != null) retireConnection(CrvRecoveryTrigger.ACTIVE_DISCONNECT)
        prepareVpn()
        manualDisconnect = false
        reconnectBudget.reset()
        reconnectBlockedForMfi = false
        lastReconnectFailure = CrvReconnectFailure.TRANSIENT
        reconnectGeneration++
        val device = usbHost.discover().firstOrNull()
        if (device == null) {
            connectionError("iPhone not detected; connect the USB cable")
            return
        }
        beginConnectionStatus("Connecting to iPhone")
        awaitingCarPlayReattach = false
        requestPermission(device)
    }

    private fun beginManualDisconnect() {
        if (destroyed) return
        manualDisconnect = true
        reconnectGeneration++
        usbTransitionGeneration.incrementAndGet()
        awaitingCarPlayReattach = false
        openCarPlayAfterPermission = false
        reconnectBudget.reset()
        diagnostics.log("Manual CarPlay disconnect requested")
        retireConnection(CrvRecoveryTrigger.MANUAL_DISCONNECT)
        showHome()
        displayedConnectionStage = CrvConnectionStage.IDLE
        renderHome(displayedConnectionStage, "Disconnected")
    }

    private fun handleCarPlayTouch(view: View, event: MotionEvent): Boolean {
        val width = view.width.coerceAtLeast(1).toDouble()
        val height = view.height.coerceAtLeast(1).toDouble()
        var introducedContact = false

        fun normalizedX(index: Int): Double = event.getX(index).toDouble() / width
        fun normalizedY(index: Int): Double = event.getY(index).toDouble() / height

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                touchSlots.reset()
                val index = event.actionIndex
                touchSlots.press(event.getPointerId(index), normalizedX(index), normalizedY(index))
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                val index = event.actionIndex
                touchSlots.press(event.getPointerId(index), normalizedX(index), normalizedY(index))
                updateTouchMoves(event, width, height)
            }

            MotionEvent.ACTION_MOVE -> {
                introducedContact = updateTouchMoves(event, width, height)
            }

            MotionEvent.ACTION_POINTER_UP -> {
                updateTouchMoves(event, width, height)
                val index = event.actionIndex
                val pointerId = event.getPointerId(index)
                touchSlots.lift(pointerId, normalizedX(index), normalizedY(index))
                sendTouchSlots()
                touchSlots.release(pointerId)
                return true
            }

            MotionEvent.ACTION_UP -> {
                val index = event.actionIndex
                val pointerId = event.getPointerId(index)
                touchSlots.lift(pointerId, normalizedX(index), normalizedY(index))
                sendTouchSlots()
                touchSlots.reset()
                view.performClick()
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                // Some API17 touch stacks no longer expose every pointer on CANCEL. Release both
                // HID slots directly so CarPlay never keeps a phantom finger pressed.
                touchSlots.cancelAll()
                sendTouchSlots()
                touchSlots.reset()
                return true
            }

            else -> return true
        }

        // A previously ignored third finger taking a free slot is a DOWN edge,
        // even though Android delivered it inside MOVE. Never discard it as old motion.
        sendTouchSlots(move = event.actionMasked == MotionEvent.ACTION_MOVE && !introducedContact)
        return true
    }

    private fun updateTouchMoves(event: MotionEvent, width: Double, height: Double): Boolean {
        var introducedContact = false
        for (index in 0 until event.pointerCount) {
            val pointerId = event.getPointerId(index)
            val x = event.getX(index).toDouble() / width
            val y = event.getY(index).toDouble() / height
            if (touchSlots.updateContact(pointerId, x, y)) introducedContact = true
        }
        return introducedContact
    }

    private fun sendTouchSlots(move: Boolean = false) {
        controller?.sendTouches(
            x0 = touchSlots.x(0),
            y0 = touchSlots.y(0),
            down0 = touchSlots.isDown(0),
            x1 = touchSlots.x(1),
            y1 = touchSlots.y(1),
            down1 = touchSlots.isDown(1),
            move = move,
        )
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
        val oldSurface = videoSurface
        if (oldSurface != null) {
            val owner = videoSurfaceOwner
            if (owner != null) owner.retireSurface { oldSurface.release() } else oldSurface.release()
        }
        // Keep the decoder/native buffer at the CarPlay protocol size. TextureView can scale the
        // composed image to the physical Honda panel without making MediaCodec renegotiate buffers.
        runCatching {
            texture.setDefaultBufferSize(
                surfaceWidth,
                surfaceHeight,
            )
        }
        videoSurface = Surface(texture)
        controller?.updateSurface(videoSurface)
        videoSurfaceOwner = controller
        maybeStartCarPlay()
        if (reconnectOnSurfaceReady && controller == null && pendingUsbSession == null) scheduleReconnect()
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
                surfaceWidth,
                surfaceHeight,
            )
        }
    }

    override fun onSurfaceTextureUpdated(texture: SurfaceTexture) = Unit

    override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean {
        // API17 cannot retarget an existing MediaCodec, so detach/recreate only the decoder.
        // Keep USB/NCM/Lockdown/iAP2/AirPlay alive across ordinary TextureView recreation.
        val oldSurface = videoSurface
        val owner = videoSurfaceOwner
        videoSurface = null
        videoSurfaceOwner = null
        val release = {
            oldSurface?.release()
            texture.release()
        }
        if (owner != null) owner.retireSurface(release) else release()
        // We own SurfaceTexture retirement until the codec has actually released it.
        return false
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

        diagnostics.log("CarPlay activity destroyed; closing controller")
        retireConnection(CrvRecoveryTrigger.ACTIVITY_DESTROYED)

        val retiredSurface = videoSurface
        val retiredOwner = videoSurfaceOwner
        videoSurface = null
        if (retiredSurface != null) {
            if (retiredOwner != null) retiredOwner.retireSurface { retiredSurface.release() }
            else retiredSurface.release()
        }

        mediaCoreMonitor?.close()
        callRouteMonitor?.close()
        callRouteMonitor = null
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
        // Shared across Activity instances so a replacement cannot race an old VPN detach.
        private val globalCleanupPending = AtomicInteger()
        private val cleanupIo: ExecutorService = Executors.newSingleThreadExecutor { task ->
            Thread(task, "crv-connection-cleanup").apply { isDaemon = true }
        }
        private const val REQUEST_VPN = 1001
        private val automaticAttemptBudget = CrvConnectionAttemptBudget()
        private const val USB_REENUMERATION_TIMEOUT_MILLIS = 15_000L
        private const val USB_REENUMERATION_POLL_MILLIS = 500L
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
