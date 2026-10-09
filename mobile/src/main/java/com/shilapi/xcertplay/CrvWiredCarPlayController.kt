package com.shilapi.xcertplay

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.IBinder
import android.view.Surface
import com.shilapi.xcertplay.airplay.AirPlayConfig
import com.shilapi.xcertplay.airplay.AirPlayContact
import com.shilapi.xcertplay.airplay.AirPlayDisplayConfig
import com.shilapi.xcertplay.airplay.AirPlaySession
import com.shilapi.xcertplay.airplay.AirPlaySessionListener
import com.shilapi.xcertplay.airplay.CarPlayMediaEngine
import com.shilapi.xcertplay.iap2.session.Iap2Session
import com.shilapi.xcertplay.mfi.Iap2MfiAuthenticationClient
import com.shilapi.xcertplay.mfi.MfiAuthenticator
import com.shilapi.xcertplay.network.CarPlayVpnService
import com.shilapi.xcertplay.transport.Iap2IdentificationConfig
import com.shilapi.xcertplay.transport.Iap2UsbMuxHost
import com.shilapi.xcertplay.transport.Iap2UsbSession
import com.shilapi.xcertplay.transport.Iap2WiredCarPlayEndpoint
import com.shilapi.xcertplay.transport.Iap2WiredControlClient
import com.shilapi.xcertplay.transport.Iap2WirelessCarPlayEndpoint
import com.shilapi.xcertplay.transport.Iap2WirelessControlClient
import com.shilapi.xcertplay.transport.Iap2WirelessIdentification
import com.shilapi.xcertplay.transport.Iap2WirelessSecurity
import com.shilapi.xcertplay.transport.IphoneCarPlayConfiguration
import com.shilapi.xcertplay.transport.IphoneUsbException
import com.shilapi.xcertplay.transport.LockdownCarKitClient
import com.shilapi.xcertplay.transport.LockdownPairingClient
import com.shilapi.xcertplay.transport.LockdownPairRecord
import com.shilapi.xcertplay.transport.LockdownTlsEngineFactory
import com.shilapi.xcertplay.transport.NcmFunctionDiscovery
import com.shilapi.xcertplay.transport.NcmClaimPolicy
import com.shilapi.xcertplay.transport.NcmUsbBridge
import com.shilapi.xcertplay.transport.UsbActiveConfiguration
import java.io.Closeable
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Android 4.2.2 / API17 CarPlay stack for the 2021 CR-V, supporting wired media and Wi-Fi handoff.
 *
 * USBMUX -> Lockdown pairing -> com.apple.carkit.service -> iAP2/MFi ->
 * USB NCM -> VpnService/AirPlay -> MediaCodec/AudioTrack.
 */
class CrvWiredCarPlayController(
    context: Context,
    private val usbManager: UsbManager,
    surface: Surface,
    private val displayWidth: Int,
    private val displayHeight: Int,
    private val report: (String) -> Unit,
    private val recordProtocolTrace: (String) -> Unit = report,
    private val reportFailure: (String, Throwable) -> Unit = { _, _ -> },
    private val mode: CrvConnectionMode = CrvConnectionMode.WIRED,
    private val onStopped: () -> Unit = {},
) : Closeable {
    private val appContext = context.applicationContext
    private val executor: ExecutorService =
        Executors.newSingleThreadExecutor { task -> Thread(task, "crv-carplay-wired").apply { isDaemon = true } }
    private val closeRequested = AtomicBoolean(false)
    private val stoppedNotified = AtomicBoolean(false)
    private val lifecycle = CrvCarPlayStateMachine { transition ->
        report(
            "Controller state ${transition.previous.phase} -> ${transition.current.phase} " +
                "reason=${transition.reason}",
        )
    }
    private val resources = CrvCarPlayResources()
    private val protocolTrace = CrvProtocolTraceRecorder(recordProtocolTrace)
    private val recovery = CrvControllerRecovery(
        closeControl = { resources.csm?.close() },
        releaseTransport = resources::releaseTransport,
        notifyStopped = ::notifyStopped,
    )

    private val airPlayState = CrvAirPlayState(appContext)
    private val identity = airPlayState.identity
    private val pairingStore = airPlayState.pairingStore()
    private val lockdownState = CrvLockdownState(appContext)
    private val deviceId = deviceId(identity.publicKey)

    private val sink = CrvApi19MediaSink(
        context = appContext,
        surface = surface,
        videoWidth = displayWidth,
        videoHeight = displayHeight,
        report = report,
    )
    private val media = CarPlayMediaEngine(
        sink = sink,
        microphoneEnabled = true,
    )

    @Volatile private var vpnBound = false
    private val vpnLatch = CountDownLatch(1)

    private val vpnConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            resources.vpnService = (binder as CarPlayVpnService.LocalBinder).service
            vpnLatch.countDown()
        }

        override fun onServiceDisconnected(name: ComponentName) {
            resources.vpnService = null
            vpnLatch.countDown()
            if (!lifecycle.isStopping()) {
                report("CarPlay network service disconnected")
                recovery.breakBlockingControl(CrvRecoveryTrigger.VPN_SERVICE_DISCONNECTED)
            }
        }
    }

    private val listener = object : AirPlaySessionListener {
        override fun onSessionActive(session: AirPlaySession) {
            if (!lifecycle.sessionActive()) {
                report("Ignoring AirPlay active before iAP2 authentication acceptance or during shutdown")
                return
            }
            resources.activeSession = session
            protocolTrace.signal(CrvProtocolLayer.CARPLAY_SESSION, "active")
            val vpn = resources.vpnService
            report("AirPlay transport attached=${vpn?.isAttached() == true} port=${vpn?.boundPort() ?: 0}")
            report("CarPlay active")
        }

        override fun onSessionEnded(session: AirPlaySession) {
            val activeSession = resources.activeSession
            if (activeSession != null && activeSession !== session) {
                report("Ignoring stale CarPlay session end while another session is active")
                return
            }
            if (activeSession === session) resources.activeSession = null
            val disposition = lifecycle.classifySessionEnd()
            report("CarPlay session ended")
            if (disposition == CrvSessionEndDisposition.HANDSHAKE_ENDED_BEFORE_ACTIVE) {
                protocolTrace.fault(
                    CrvProtocolLayer.CARPLAY_SESSION,
                    "session-ended",
                    "peer-eof-before-active",
                )
            } else {
                protocolTrace.signal(CrvProtocolLayer.CARPLAY_SESSION, "session-ended")
            }
            val recoveryMessage = if (
                disposition == CrvSessionEndDisposition.HANDSHAKE_ENDED_BEFORE_ACTIVE
            ) {
                "CarPlay handshake ended before active; restarting controller"
            } else {
                "CarPlay TCP session ended; restarting controller"
            }
            report(recoveryMessage)
            recovery.breakBlockingControl(CrvRecoveryTrigger.TCP_EOF)?.let { error ->
                report(
                    "CarPlay TCP recovery failed: ${error.javaClass.simpleName}: " +
                        (error.message ?: "no message"),
                )
            }
        }

        override fun onTransportError(message: String) {
            val vpn = resources.vpnService
            protocolTrace.fault(
                CrvProtocolLayer.CARPLAY_SESSION,
                "transport",
                transportFaultClass(message),
            )
            report("AirPlay transport attached=${vpn?.isAttached() == true} port=${vpn?.boundPort() ?: 0}")
            report("CarPlay transport error: $message")
            // Break the blocking wired control loop so the worker can tear the complete stack down.
            recovery.breakBlockingControl(recoveryTriggerForTransport(message))
        }

        override fun onDebugLog(message: String) {
            when {
                message.startsWith("airplay rx SETUP", ignoreCase = true) ->
                    protocolTrace.record(
                        CrvProtocolLayer.CARPLAY_SESSION,
                        CrvProtocolDirection.HOST_TO_DEVICE,
                        "rtsp-setup",
                        controlBodyBytes(message),
                    )
                message.startsWith("airplay tx SETUP", ignoreCase = true) ->
                    protocolTrace.record(
                        CrvProtocolLayer.CARPLAY_SESSION,
                        CrvProtocolDirection.DEVICE_TO_HOST,
                        "rtsp-setup",
                        controlBodyBytes(message),
                    )
            }
            if (
                message.contains("SETUP", ignoreCase = true) ||
                message.contains("pair", ignoreCase = true) ||
                message.contains("video", ignoreCase = true) ||
                message.contains("event", ignoreCase = true) ||
                message.contains("handler failed", ignoreCase = true) ||
                message.contains("control closing", ignoreCase = true)
            ) {
                report(message)
            }
        }
    }

    fun start(device: UsbDevice, usbSession: Iap2UsbSession) {
        protocolTrace.signal(CrvProtocolLayer.USB, "attached", detail = mode.name)
        lifecycle.start()
        executor.execute {
            try {
                runWired(device, usbSession)
            } catch (error: Throwable) {
                val userRequestedStop = lifecycle.isStopping()
                if (!userRequestedStop) {
                    val failedLayer = traceLayerFor(lifecycle.snapshot().phase)
                    lifecycle.fail(error.javaClass.simpleName)
                    protocolTrace.fault(
                        failedLayer,
                        "controller",
                        error.javaClass.simpleName,
                    )
                    reportFailure("CarPlay controller", error)
                    report(
                        "CarPlay failed: " + error.javaClass.name + ": " +
                            (error.message ?: "no message"),
                    )
                }
            } finally {
                lifecycle.requestStop("worker-finished")
                recovery.completeWorker(
                    closeUsbSession = { usbSession.close() },
                    finishLifecycle = {
                        if (lifecycle.snapshot().phase == CrvControllerPhase.STOPPING) {
                            lifecycle.finishStop()
                        }
                    },
                )
            }
        }
    }

    fun updateSurface(surface: Surface?) {
        sink.updateSurface(surface)
    }

    fun sendTouch(x: Double, y: Double, down: Boolean): Boolean {
        val session = resources.activeSession ?: return false
        return session.sendTouch(
            listOf(
                AirPlayContact(
                    id = 0,
                    x = x.coerceIn(0.0, 1.0),
                    y = y.coerceIn(0.0, 1.0),
                    down = down,
                ),
            ),
        )
    }

    private fun runWired(device: UsbDevice, usbSession: Iap2UsbSession) {
        check(!lifecycle.isStopping()) { "controller is stopping" }

        report(CrvMfiProvider.status(appContext))

        // Match DiPlay's wired bring-up: validate the complete transport before crossing the
        // accessory-authentication boundary. This does not bypass MFi: authentication is loaded
        // immediately before iAP2 identification and installed into the AirPlay listener first.
        // NCM is opened before Lockdown/iAP2 so the iPhone sees both CarPlay interfaces active.
        val expectedKernelNcm = if (mode == CrvConnectionMode.WIRED) {
            report("Opening CDC-NCM data path")
            inspectExpectedKernelNcm(device).also { expected ->
                report("Kernel NCM identity " + CrvUsbKernelProbe.describeExpected(expected))
            }
        } else {
            null
        }
        var kernelNcm = expectedKernelNcm?.let { expected ->
            CrvUsbKernelProbe.waitForKernelNcm(expected, KERNEL_NCM_WAIT_MILLIS)?.also { network ->
                reportKernelNcmReady(network)
            }
        }
        var kernelBringUp: CrvUsbKernelProbe.KernelBringUpResult? = null
        if (mode == CrvConnectionMode.WIRED && kernelNcm == null && expectedKernelNcm != null) {
            val bringUp = CrvUsbKernelProbe.tryBringUpKernelNcm(expectedKernelNcm)
            kernelBringUp = bringUp
            report(
                "Honda kernel CDC-NCM bring-up interface=${bringUp.interfaceName ?: "none"} " +
                    "attempted=${bringUp.attempted} result=${bringUp.resultCode ?: -1}" +
                    (bringUp.error?.let { " detail=$it" } ?: ""),
            )
            if (bringUp.interfaceName != null) {
                kernelNcm = CrvUsbKernelProbe.waitForKernelNcm(
                    expectedKernelNcm,
                    KERNEL_NCM_BRINGUP_WAIT_MILLIS,
                )?.also { network ->
                    reportKernelNcmReady(network)
                }
            }
        }
        val ncmBridge = if (mode == CrvConnectionMode.WIRED && kernelNcm == null) {
            val expected = expectedKernelNcm
                ?: throw IphoneUsbException.DeviceUnavailable("Kernel NCM identity is unavailable")
            val conflict = CrvUsbKernelProbe.findConfigurationConflict(expected)
            if (conflict != null) {
                report("USB KERNEL/DEVICE CONFIGURATION CONFLICT: $conflict")
                throw IphoneUsbException.Protocol(
                    "Kernel USB configuration does not match active iPhone CarPlay configuration",
                )
            }
            val fallbackReason = checkNotNull(kernelBringUp).fallbackReason.diagnostic
            report(
                "Honda kernel CDC-NCM fallback reason=$fallbackReason " +
                    "kernelDriverDetachAllowed=false",
            )
            try {
                openNcm(device, NcmClaimPolicy.PRESERVE_KERNEL_DRIVER).also {
                    resources.ncm = it
                    report("CDC-NCM ready backend=userspace experimental safeClaim=true")
                }
            } catch (error: IphoneUsbException.InterfaceBusy) {
                report(
                    "Honda CDC-NCM fallback blocked classification=usb-interface-resource-conflict " +
                        "kernelDriverDetachAllowed=false detail=${error.message}",
                )
                throw error
            }
        } else {
            null
        }
        if (mode == CrvConnectionMode.WIRED) {
            lifecycle.ncmReady()
            protocolTrace.signal(CrvProtocolLayer.NCM, "ready")
        }

        val host = Iap2UsbMuxHost.open(
            pipe = usbSession,
            onDiagnostic = { report(it) },
        )
        resources.mux = host
        report("USBMUX ready")
        lifecycle.usbMuxReady()
        protocolTrace.signal(CrvProtocolLayer.USBMUX, "ready")

        val carKitClient = LockdownCarKitClient(host)
        var pairRecord = lockdownState.load()
        val carkit = if (pairRecord != null) {
            report("Using saved iPhone pairing")
            try {
                carKitClient.open(pairRecord, LABEL)
            } catch (error: Throwable) {
                if (!CrvPairingFailurePolicy.isPairRejection(error)) throw error
                report("Saved iPhone pairing rejected; pairing again")
                lockdownState.clear()
                pairRecord = pairNew(host)
                carKitClient.open(pairRecord, LABEL)
            }
        } else {
            pairRecord = pairNew(host)
            carKitClient.open(pairRecord, LABEL)
        }
        preflightLockdownTls(checkNotNull(pairRecord))
        report("iPhone Lockdown ready")
        lifecycle.lockdownReady()
        protocolTrace.signal(CrvProtocolLayer.LOCKDOWN, "ready")
        val session = Iap2Session.open(
            underlying = carkit,
            traceContext = "crv-wired",
            onTrace = { line -> if (line.contains("FAIL") || line.contains("READY")) report(line) },
        )
        resources.csm = session
        report("iAP2 carkit channel ready")
        lifecycle.iap2Ready()
        protocolTrace.signal(CrvProtocolLayer.USBMUX, "iap2-ready")

        if (mode == CrvConnectionMode.WIFI_HANDOFF) {
            val mfi = loadMfi()
            report("MFi authentication ready source=${resources.mfiLease?.source ?: "unknown"}")
            runWirelessHandoff(session, mfi)
            return
        }

        val hostMac = kernelNcm?.hardwareAddress
            ?.takeIf { it.size == 6 }
            ?: ncmBridge?.hostMac
            ?: macBytes(deviceId)
        val advertisedLinkLocal = kernelNcm?.linkLocal?.hostAddress
            ?.substringBefore('%')
            ?: LINK_LOCAL
        val airPlay = airPlayConfig(deviceId)
        val vpn = awaitVpnService()
            ?: throw IphoneUsbException.DeviceUnavailable("CarPlay network service did not bind")
        report("CarPlay network service ready")

        var activeKernelNcm = kernelNcm
        var activeNcmBridge = ncmBridge
        var activeHostMac = hostMac
        var activeLinkLocal = advertisedLinkLocal

        var attached = if (activeKernelNcm != null) {
            report("Using Honda kernel CDC-NCM backend interface=${activeKernelNcm.interfaceName}")
            vpn.attachKernelNetwork(
                bindAddress = activeKernelNcm.linkLocal,
                config = airPlay,
                identity = identity,
                pairings = pairingStore,
                mfi = null,
                listener = listener,
                media = media,
            )
        } else {
            val wiredNcm = activeNcmBridge
                ?: throw IphoneUsbException.DeviceUnavailable("CDC-NCM data path is unavailable")
            vpn.attach(
                ncm = wiredNcm,
                linkLocal = activeLinkLocal,
                hostMac = activeHostMac,
                config = airPlay,
                identity = identity,
                pairings = pairingStore,
                mfi = null,
                listener = listener,
                media = media,
            )
        }

        if (attached is CarPlayVpnService.AttachResult.Failed && activeKernelNcm != null) {
            report(
                "Kernel CDC-NCM AirPlay bind failed: ${attached.message}; " +
                    "rechecking Honda kernel link before fallback",
            )
            val expected = expectedKernelNcm
                ?: throw IphoneUsbException.DeviceUnavailable("Kernel NCM identity is unavailable")
            val refreshed = CrvUsbKernelProbe.waitForKernelNcm(
                expected,
                KERNEL_NCM_RETRY_WAIT_MILLIS,
            )
            if (refreshed != null) {
                activeKernelNcm = refreshed
                activeHostMac = refreshed.hardwareAddress
                    ?.takeIf { it.size == 6 }
                    ?: activeHostMac
                activeLinkLocal = refreshed.linkLocal.hostAddress.substringBefore('%')
                report(
                    "Honda kernel CDC-NCM still healthy interface=${refreshed.interfaceName}; " +
                        "retrying scoped AirPlay bind",
                )
                attached = vpn.attachKernelNetwork(
                    bindAddress = refreshed.linkLocal,
                    config = airPlay,
                    identity = identity,
                    pairings = pairingStore,
                    mfi = null,
                    listener = listener,
                    media = media,
                )
                if (attached is CarPlayVpnService.AttachResult.Failed) {
                    throw IphoneUsbException.DeviceUnavailable(
                        "Kernel CDC-NCM is healthy but AirPlay bind failed after retry: " +
                            attached.message,
                    )
                }
            } else {
                report(
                    "Honda kernel CDC-NCM disappeared after bind failure; " +
                        "using userspace NCM fallback",
                )
                activeKernelNcm = null
                val fallback = openNcm(device, NcmClaimPolicy.PRESERVE_KERNEL_DRIVER)
                resources.ncm = fallback
                activeNcmBridge = fallback
                activeHostMac = fallback.hostMac ?: macBytes(deviceId)
                activeLinkLocal = LINK_LOCAL
                attached = vpn.attach(
                    ncm = fallback,
                    linkLocal = activeLinkLocal,
                    hostMac = activeHostMac,
                    config = airPlay,
                    identity = identity,
                    pairings = pairingStore,
                    mfi = null,
                    listener = listener,
                    media = media,
                )
            }
        }

        when (attached) {
            is CarPlayVpnService.AttachResult.Failed ->
                throw IphoneUsbException.DeviceUnavailable(
                    "NCM/AirPlay attach failed: ${attached.message}",
                )
            else -> resources.vpnAttached = true
        }

        val airPlayPort = vpn.boundPort()
            ?: throw IphoneUsbException.DeviceUnavailable("AirPlay listener did not bind")
        report(
            "AirPlay listening on $activeLinkLocal:$airPlayPort " +
                "backend=${if (activeKernelNcm != null) "kernel" else "userspace"}",
        )
        lifecycle.networkReady()

        val usbMuxInterfaceNumber = usbSession.usbMuxInterfaceNumber
        report("CarPlay USBMUX interface=$usbMuxInterfaceNumber (claimed)")

        val identification = Iap2IdentificationConfig(
            name = "Honda CR-V CarPlay",
            modelIdentifier = "CR-V-2021",
            manufacturer = "Honda",
            serialNumber = "CRV-${deviceId.replace(":", "")}",
            firmwareVersion = "1.0",
            hardwareVersion = "2021",
            carPlayUsbInterfaceNumber = usbMuxInterfaceNumber,
            locationInformationEnabled = false,
            vehicleStatusEnabled = false,
            vehicleSpeedEnabled = false,
        )

        val endpoint = Iap2WiredCarPlayEndpoint(
            ipv6Addresses = listOf(activeLinkLocal),
            airPlayPort = airPlayPort,
            publicKey = identity.publicKeyHex,
            sourceVersion = SOURCE_VERSION,
            deviceIdentifier = activeHostMac.macString(),
        )

        report("Transport pre-auth ready")
        report(CrvMfiProvider.status(appContext))
        val mfi = try {
            loadMfi()
        } catch (error: java.io.FileNotFoundException) {
            report(
                "MFi identity missing; transport pre-auth verified. Provision " +
                    CrvMfiAssets.provisioningDirectory(appContext),
            )
            throw error
        } catch (error: Exception) {
            report(
                "MFi identity invalid; transport pre-auth verified type=" +
                    error.javaClass.simpleName,
            )
            throw error
        }
        report("MFi authentication ready source=${resources.mfiLease?.source ?: "unknown"}")
        vpn.updateMfiAuthenticator(mfi)
        protocolTrace.signal(CrvProtocolLayer.MFI, "loaded")

        report("Starting iAP2 identification/MFi")
        lifecycle.sessionControlStarted()
        protocolTrace.signal(CrvProtocolLayer.CARPLAY_SESSION, "control-start")
        Iap2WiredControlClient(
            session = session,
            mfi = Iap2MfiAuthenticationClient(mfi),
        ).run(
            identification = identification,
            endpoint = endpoint,
            availableCurrentMilliAmps = AVAILABLE_CURRENT_MA,
            timeoutMillis = Iap2WiredControlClient.NO_TIMEOUT_MILLIS,
            onProgress = { message ->
                if (message == "iap2 authentication accepted" && !lifecycle.isStopping()) {
                    lifecycle.authenticated()
                    protocolTrace.signal(CrvProtocolLayer.MFI, "accepted")
                }
                report(message)
            },
        ).also { result ->
            report("iAP2 wired control ended terminal=${result.terminal} stage=${result.stage} " +
                "carPlayStartSessions=${result.carPlayStartSessionsSent}")
        }
    }

    private fun runWirelessHandoff(session: Iap2Session, mfi: MfiAuthenticator) {
        report("Starting Wi-Fi CarPlay handoff")
        val hotspot = CrvApi19WirelessHotspot(appContext, report)
        resources.wifiHotspot = hotspot
        val hotspotInfo = hotspot.start()

        val airPlay = airPlayConfig(deviceId)
        val vpn = awaitVpnService()
            ?: throw IphoneUsbException.DeviceUnavailable("CarPlay VPN service did not bind")
        report("CarPlay network service ready")
        when (
            val attached = vpn.attachWireless(
                bindAddress = hotspotInfo.hostAddress,
                config = airPlay,
                identity = identity,
                pairings = pairingStore,
                mfi = mfi,
                listener = listener,
                media = media,
            )
        ) {
            is CarPlayVpnService.AttachResult.Failed ->
                throw IphoneUsbException.DeviceUnavailable(
                    "Wi-Fi/AirPlay attach failed: ${attached.message}",
                )
            else -> resources.vpnAttached = true
        }

        val airPlayPort = vpn.boundPort()
            ?: throw IphoneUsbException.DeviceUnavailable("Wi-Fi AirPlay listener did not bind")

        resources.bonjour = CrvApi19BonjourAdvertiser(
            context = appContext,
            serviceName = "Honda CR-V",
            port = airPlayPort,
            report = report,
        ).also { it.start() }

        val bluetoothMac = bluetoothTransportIdentifier()
        report(
            "Wi-Fi Bluetooth transport id source=" +
                if (bluetoothMac == deviceId) "derived" else "adapter",
        )

        val identification = Iap2IdentificationConfig(
            name = "Honda CR-V CarPlay",
            modelIdentifier = "CR-V-2021",
            manufacturer = "Honda",
            serialNumber = "CRV-${deviceId.replace(":", "")}",
            firmwareVersion = "1.0",
            hardwareVersion = "2021",
            wireless = Iap2WirelessIdentification(
                bluetoothMac = bluetoothMac,
                ssid = hotspotInfo.ssid,
            ),
        )

        val endpoint = Iap2WirelessCarPlayEndpoint(
            ssid = hotspotInfo.ssid,
            passphrase = hotspotInfo.passphrase,
            channel = hotspotInfo.channel,
            security = Iap2WirelessSecurity.WPA_WPA2,
            ipAddresses = listOf(hotspotInfo.hostAddress.hostAddress),
            airPlayPort = airPlayPort,
            deviceIdentifier = deviceId,
            publicKey = identity.publicKeyHex,
            sourceVersion = SOURCE_VERSION,
        )

        report(
            "Wi-Fi CarPlay endpoint ready ssid=${hotspotInfo.ssid} " +
                "port=$airPlayPort",
        )
        lifecycle.networkReady()
        lifecycle.sessionControlStarted()
        Iap2WirelessControlClient(
            session = session,
            mfi = Iap2MfiAuthenticationClient(mfi),
        ).run(
            identification = identification,
            endpoint = endpoint,
            timeoutMillis = Iap2WirelessControlClient.NO_TIMEOUT_MILLIS,
            onReady = { report("Wi-Fi CarPlay credentials ready") },
            onProgress = { message ->
                if (message == "iap2 authentication accepted" && !lifecycle.isStopping()) {
                    lifecycle.authenticated()
                    protocolTrace.signal(CrvProtocolLayer.MFI, "accepted")
                }
                report(message)
            },
        )
    }

    private fun preflightLockdownTls(pairRecord: LockdownPairRecord) {
        val supported = LockdownTlsEngineFactory.supportedSocketProtocols(pairRecord)
        val enabled = listOf("TLSv1.3", "TLSv1.2").filter(supported.toSet()::contains)
        if (enabled.isEmpty()) {
            throw IphoneUsbException.Protocol(
                "Platform SSLSocket supports neither TLSv1.2 nor TLSv1.3",
            )
        }
        report("Lockdown TLS preflight ready protocols=" + enabled.joinToString(","))
    }

    private fun pairNew(host: Iap2UsbMuxHost): LockdownPairRecord {
        val paired = LockdownPairingClient(host).pair(
            label = LABEL,
            hostId = UUID.randomUUID().toString().uppercase(Locale.US),
            systemBuid = UUID.randomUUID().toString().uppercase(Locale.US),
            totalTimeoutMillis = PAIR_TIMEOUT_MILLIS,
            isCancelled = { lifecycle.isStopping() },
        )
        lockdownState.save(paired.pairRecord)
        report("iPhone Lockdown paired and saved")
        return paired.pairRecord
    }

    private fun awaitVpnService(): CarPlayVpnService? {
        resources.vpnService?.let { return it }
        report("Binding CarPlay network service")
        bindVpn()
        return try {
            if (vpnLatch.await(VPN_CONNECT_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)) {
                resources.vpnService
            } else {
                null
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            null
        }
    }

    private fun bindVpn() {
        if (vpnBound || lifecycle.isStopping()) return
        vpnBound = true
        try {
            if (!appContext.bindService(
                    Intent(appContext, CarPlayVpnService::class.java),
                    vpnConnection,
                    Context.BIND_AUTO_CREATE,
                )
            ) {
                vpnBound = false
                vpnLatch.countDown()
            }
        } catch (_: Throwable) {
            vpnBound = false
            vpnLatch.countDown()
        }
    }

    private fun unbindVpn() {
        if (!vpnBound) return
        vpnBound = false
        runCatching { appContext.unbindService(vpnConnection) }
        resources.vpnService = null
    }

    private fun reportKernelNcmReady(network: CrvUsbKernelProbe.KernelNcmNetwork) {
        report(
            "Honda kernel CDC-NCM ready interface=${network.interfaceName} " +
                "ipv6=${network.linkLocal.hostAddress} " +
                "kernelCfg=${network.kernelConfigurationValue} " +
                "usbIface=${network.usbInterfaceNumber} sysfs=${network.sysfsInterfaceName}",
        )
    }

    private fun inspectExpectedKernelNcm(device: UsbDevice): CrvUsbKernelProbe.ExpectedUsbNcm {
        val connection = usbManager.openDevice(device)
            ?: throw IphoneUsbException.DeviceUnavailable(
                "Could not open iPhone USB connection for kernel NCM identity",
            )
        try {
            val activeConfiguration = UsbActiveConfiguration.readValue(connection)
                ?: throw IphoneUsbException.Protocol(
                    "Could not read active iPhone USB configuration for kernel NCM identity",
                )
            val rawDescriptors = connection.rawDescriptors
            val function = NcmFunctionDiscovery.find(
                device,
                rawDescriptors,
                activeConfiguration,
            ) ?: throw IphoneUsbException.Protocol(
                "Active USB configuration $activeConfiguration exposes no CDC-NCM function",
            )
            val numbers = parseUsbBusAndDevice(device.deviceName)
            report(
                "Device/kernel NCM expected activeCfg=$activeConfiguration " +
                    "ctrl=${function.control.id} data=${function.data.id} " +
                    "device=${device.deviceName}",
            )
            CrvUsbKernelProbe.collect().forEach(report)
            return CrvUsbKernelProbe.ExpectedUsbNcm(
                configurationValue = activeConfiguration,
                interfaceNumbers = setOf(function.control.id, function.data.id),
                vendorId = device.vendorId,
                productId = device.productId,
                busNumber = numbers?.first,
                deviceNumber = numbers?.second,
            )
        } finally {
            connection.close()
        }
    }

    private fun parseUsbBusAndDevice(deviceName: String): Pair<Int, Int>? {
        val parts = deviceName.split('/').filter { it.isNotEmpty() }
        if (parts.size < 2) return null
        val bus = parts[parts.size - 2].toIntOrNull() ?: return null
        val dev = parts.last().toIntOrNull() ?: return null
        return bus to dev
    }

    private fun openNcm(device: UsbDevice, claimPolicy: NcmClaimPolicy): NcmUsbBridge {
        report(
            "USB layout " +
                (0 until device.interfaceCount).joinToString(" ") { index ->
                    val usbInterface = device.getInterface(index)
                    "#${usbInterface.id}:" +
                        "${usbInterface.interfaceClass.toString(16)}." +
                        "${usbInterface.interfaceSubclass.toString(16)}." +
                        "${usbInterface.interfaceProtocol.toString(16)}" +
                        "x${usbInterface.endpointCount}"
                },
        )
        val connection = usbManager.openDevice(device)
            ?: throw IphoneUsbException.DeviceUnavailable("Could not open iPhone NCM USB connection")
        val activeConfiguration = UsbActiveConfiguration.readValue(connection)
            ?: run {
                connection.close()
                throw IphoneUsbException.Protocol("Could not read active iPhone USB configuration")
            }
        report("USB active configuration=$activeConfiguration")
        val rawDescriptors = connection.rawDescriptors
        val activeLayout = UsbActiveConfiguration.interfaces(rawDescriptors, activeConfiguration)
        report(
            "USB active layout " + activeLayout.joinToString(" ") { descriptor ->
                "#${descriptor.number}/${descriptor.alternateSetting}:" +
                    "${descriptor.interfaceClass.toString(16)}." +
                    "${descriptor.interfaceSubclass.toString(16)}." +
                    "${descriptor.interfaceProtocol.toString(16)}" +
                    "x${descriptor.endpointCount}"
            },
        )
        CrvUsbKernelProbe.collect().forEach(report)
        val function = NcmFunctionDiscovery.find(
            device,
            rawDescriptors,
            activeConfiguration,
        ) ?: run {
            connection.close()
            throw IphoneUsbException.Protocol(
                "Active USB configuration $activeConfiguration exposes no CDC-NCM function",
            )
        }
        report(
            "NCM selected cfg=${function.configurationValue ?: -1} " +
                "ctrl=${function.control.id} " +
                "data=${function.data.id}/${function.dataAlternateSetting} " +
                "in=0x${function.bulkIn.address.toString(16)} " +
                "out=0x${function.bulkOut.address.toString(16)}",
        )
        return NcmUsbBridge.open(connection, function, claimPolicy)
    }

    private fun loadMfi(): MfiAuthenticator {
        resources.mfiLease?.let { return it.client }
        return CrvMfiProvider.acquire(appContext, report).also {
            resources.mfiLease = it
        }.client
    }

    @SuppressLint("MissingPermission", "HardwareIds")
    private fun bluetoothTransportIdentifier(): String {
        val value = runCatching { BluetoothAdapter.getDefaultAdapter()?.address }
            .getOrNull()
            ?.uppercase(Locale.US)
        return if (
            value != null &&
            value.matches(Regex("[0-9A-F]{2}(:[0-9A-F]{2}){5}")) &&
            value != "02:00:00:00:00:00"
        ) {
            value
        } else {
            deviceId
        }
    }

    private fun airPlayConfig(id: String): AirPlayConfig = AirPlayConfig(
        deviceName = "Honda CR-V",
        deviceId = id,
        btMac = id,
        sourceVersion = SOURCE_VERSION,
        main = AirPlayDisplayConfig(
            widthPixels = displayWidth,
            heightPixels = displayHeight,
            fps = 30,
        ),
        rightHandDrive = false,
        hevc = false,
        microphone = true,
        opus = false,
        manufacturer = "Honda",
        model = "CR-V 2021",
        oemLabel = "Honda",
        videoInCar = false,
    )

    private fun controlBodyBytes(message: String): Int =
        Regex("""body=(\d+)""").find(message)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 0

    private fun transportFaultClass(message: String): String = when {
        message.contains("EOF", ignoreCase = true) -> "peer-eof"
        message.contains("timeout", ignoreCase = true) -> "timeout"
        message.contains("SETUP", ignoreCase = true) -> "setup-error"
        message.contains("closed", ignoreCase = true) -> "closed"
        else -> "transport-error"
    }

    private fun traceLayerFor(phase: CrvControllerPhase): CrvProtocolLayer = when (phase) {
        CrvControllerPhase.NEW,
        CrvControllerPhase.STARTING -> CrvProtocolLayer.USB
        CrvControllerPhase.NCM_READY -> CrvProtocolLayer.NCM
        CrvControllerPhase.USBMUX_READY,
        CrvControllerPhase.IAP2_READY -> CrvProtocolLayer.USBMUX
        CrvControllerPhase.LOCKDOWN_READY -> CrvProtocolLayer.LOCKDOWN
        CrvControllerPhase.NETWORK_READY,
        CrvControllerPhase.SESSION_CONTROL -> CrvProtocolLayer.MFI
        CrvControllerPhase.AUTHENTICATED -> CrvProtocolLayer.CARPLAY_SESSION
        CrvControllerPhase.FAILED,
        CrvControllerPhase.STOPPING,
        CrvControllerPhase.STOPPED -> CrvProtocolLayer.CARPLAY_SESSION
    }

    override fun close() = close(CrvRecoveryTrigger.ACTIVE_DISCONNECT)

    internal fun close(reason: CrvRecoveryTrigger) {
        if (!closeRequested.compareAndSet(false, true)) return
        lifecycle.requestStop("close:${reason.name}")
        recovery.breakBlockingControl(reason)
        resources.releaseTransport()
        unbindVpn()
        sink.close()
        executor.shutdownNow()
        if (lifecycle.snapshot().phase == CrvControllerPhase.STOPPING) {
            lifecycle.finishStop()
        }
        notifyStopped()
    }

    private fun notifyStopped() {
        if (stoppedNotified.compareAndSet(false, true)) {
            runCatching(onStopped)
        }
    }

    private fun deviceId(publicKey: ByteArray): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(publicKey).copyOfRange(0, 6)
        bytes[0] = ((bytes[0].toInt() and 0xfc) or 0x02).toByte()
        return bytes.macString().uppercase(Locale.US)
    }

    private fun macBytes(value: String): ByteArray =
        value.split(":").map { it.toInt(16).toByte() }.toByteArray()

    private fun ByteArray.macString(): String =
        joinToString(":") { "%02x".format(it.toInt() and 0xff) }

    companion object {
        private const val LABEL = "CarPlay CR-V"
        private const val SOURCE_VERSION = "950.7.1"
        private const val LINK_LOCAL = "fe80::2"
        private const val AVAILABLE_CURRENT_MA = 1500
        private const val PAIR_TIMEOUT_MILLIS = 5 * 60_000L
        private const val VPN_CONNECT_TIMEOUT_MILLIS = 5_000L
        private const val KERNEL_NCM_WAIT_MILLIS = 2_500L
        private const val KERNEL_NCM_BRINGUP_WAIT_MILLIS = 4_000L
        private const val KERNEL_NCM_RETRY_WAIT_MILLIS = 1_000L
    }
}
