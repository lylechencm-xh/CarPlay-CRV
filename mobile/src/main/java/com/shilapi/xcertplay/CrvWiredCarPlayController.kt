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
import com.shilapi.xcertplay.transport.NcmUsbBridge
import com.shilapi.xcertplay.transport.UsbActiveConfiguration
import java.io.Closeable
import java.security.GeneralSecurityException
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
    private val mode: CrvConnectionMode = CrvConnectionMode.WIRED,
    private val onStopped: () -> Unit = {},
) : Closeable {
    private val appContext = context.applicationContext
    private val executor: ExecutorService =
        Executors.newSingleThreadExecutor { task -> Thread(task, "crv-carplay-wired").apply { isDaemon = true } }
    private val closed = AtomicBoolean(false)
    private val stoppedNotified = AtomicBoolean(false)

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

    @Volatile private var activeSession: AirPlaySession? = null
    @Volatile private var mux: Iap2UsbMuxHost? = null
    @Volatile private var csm: Iap2Session? = null
    @Volatile private var ncm: NcmUsbBridge? = null
    @Volatile private var vpnAttached = false
    @Volatile private var wifiHotspot: CrvApi19WirelessHotspot? = null
    @Volatile private var bonjour: CrvApi19BonjourAdvertiser? = null
    @Volatile private var vpnService: CarPlayVpnService? = null
    @Volatile private var vpnBound = false
    @Volatile private var mfiLease: CrvMfiProvider.Lease? = null
    private val vpnLatch = CountDownLatch(1)

    private val vpnConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            vpnService = (binder as CarPlayVpnService.LocalBinder).service
            vpnLatch.countDown()
        }

        override fun onServiceDisconnected(name: ComponentName) {
            vpnService = null
            vpnLatch.countDown()
            if (!closed.get()) {
                report("CarPlay network service disconnected")
                runCatching { csm?.close() }
            }
        }
    }

    private val listener = object : AirPlaySessionListener {
        override fun onSessionActive(session: AirPlaySession) {
            activeSession = session
            val vpn = vpnService
            report("AirPlay transport attached=${vpn?.isAttached() == true} port=${vpn?.boundPort() ?: 0}")
            report("CarPlay active")
        }

        override fun onSessionEnded(session: AirPlaySession) {
            if (activeSession === session) activeSession = null
            report("CarPlay session ended")
        }

        override fun onTransportError(message: String) {
            val vpn = vpnService
            report("AirPlay transport attached=${vpn?.isAttached() == true} port=${vpn?.boundPort() ?: 0}")
            report("CarPlay transport error: $message")
            // Break the blocking wired control loop so the worker can tear the complete stack down.
            runCatching { csm?.close() }
        }

        override fun onDebugLog(message: String) {
            if (
                message.contains("SETUP", ignoreCase = true) ||
                message.contains("pair", ignoreCase = true) ||
                message.contains("video", ignoreCase = true)
            ) {
                report(message)
            }
        }
    }

    fun start(device: UsbDevice, usbSession: Iap2UsbSession) {
        executor.execute {
            try {
                runWired(device, usbSession)
            } catch (error: Throwable) {
                if (!closed.get()) {
                    report("CarPlay failed: ${error.message ?: error.javaClass.simpleName}")
                }
            } finally {
                cleanupAfterFailure()
                runCatching { usbSession.close() }
                notifyStopped()
            }
        }
    }

    fun updateSurface(surface: Surface?) {
        sink.updateSurface(surface)
    }

    fun sendTouch(x: Double, y: Double, down: Boolean): Boolean {
        val session = activeSession ?: return false
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
        check(!closed.get()) { "controller is closed" }

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
        val kernelNcm = expectedKernelNcm?.let { expected ->
            CrvUsbKernelProbe.waitForKernelNcm(expected, KERNEL_NCM_WAIT_MILLIS)?.also { network ->
                report(
                    "Honda kernel CDC-NCM ready interface=${network.interfaceName} " +
                        "ipv6=${network.linkLocal.hostAddress} " +
                        "kernelCfg=${network.kernelConfigurationValue} " +
                        "usbIface=${network.usbInterfaceNumber} sysfs=${network.sysfsInterfaceName}",
                )
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
            // A bound cdc_ncm driver is not enough on this Honda build: Android leaves the
            // corresponding netdev down with no link-local address. That is a kernel-backend
            // readiness issue, not a failure of the CarPlay NCM function itself. Continue with
            // the userspace bridge, which owns the USB interfaces directly.
            report("Honda kernel CDC-NCM not network-ready; userspace NCM fallback active")
            openNcm(device).also {
                ncm = it
                report("CDC-NCM ready backend=userspace experimental")
            }
        } else {
            null
        }

        val host = Iap2UsbMuxHost.open(
            pipe = usbSession,
            onDiagnostic = { report(it) },
        )
        mux = host
        report("USBMUX ready")

        val carKitClient = LockdownCarKitClient(host)
        var pairRecord = lockdownState.load()
        val carkit = if (pairRecord != null) {
            report("Using saved iPhone pairing")
            try {
                carKitClient.open(pairRecord, LABEL)
            } catch (error: Throwable) {
                if (!isPairRejection(error)) throw error
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
        val session = Iap2Session.open(
            underlying = carkit,
            traceContext = "crv-wired",
            onTrace = { line -> if (line.contains("FAIL") || line.contains("READY")) report(line) },
        )
        csm = session
        report("iAP2 carkit channel ready")

        if (mode == CrvConnectionMode.WIFI_HANDOFF) {
            val mfi = loadMfi()
            report("MFi authentication ready source=${mfiLease?.source ?: "unknown"}")
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
                val fallback = openNcm(device)
                ncm = fallback
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
            else -> vpnAttached = true
        }

        val airPlayPort = vpn.boundPort()
            ?: throw IphoneUsbException.DeviceUnavailable("AirPlay listener did not bind")
        report(
            "AirPlay listening on $activeLinkLocal:$airPlayPort " +
                "backend=${if (activeKernelNcm != null) "kernel" else "userspace"}",
        )

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
        report("MFi authentication ready source=${mfiLease?.source ?: "unknown"}")
        vpn.updateMfiAuthenticator(mfi)

        report("Starting iAP2 identification/MFi")
        Iap2WiredControlClient(
            session = session,
            mfi = Iap2MfiAuthenticationClient(mfi),
        ).run(
            identification = identification,
            endpoint = endpoint,
            availableCurrentMilliAmps = AVAILABLE_CURRENT_MA,
            timeoutMillis = Iap2WiredControlClient.NO_TIMEOUT_MILLIS,
            onProgress = { report(it) },
        )
    }

    private fun runWirelessHandoff(session: Iap2Session, mfi: MfiAuthenticator) {
        report("Starting Wi-Fi CarPlay handoff")
        val hotspot = CrvApi19WirelessHotspot(appContext, report)
        wifiHotspot = hotspot
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
            else -> vpnAttached = true
        }

        val airPlayPort = vpn.boundPort()
            ?: throw IphoneUsbException.DeviceUnavailable("Wi-Fi AirPlay listener did not bind")

        bonjour = CrvApi19BonjourAdvertiser(
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

        Iap2WirelessControlClient(
            session = session,
            mfi = Iap2MfiAuthenticationClient(mfi),
        ).run(
            identification = identification,
            endpoint = endpoint,
            timeoutMillis = Iap2WirelessControlClient.NO_TIMEOUT_MILLIS,
            onReady = { report("Wi-Fi CarPlay credentials ready") },
            onProgress = { report(it) },
        )
    }

    private fun preflightLockdownTls(pairRecord: LockdownPairRecord) {
        val engine = LockdownTlsEngineFactory.create(pairRecord)
        report(
            "Lockdown TLS preflight ready protocols=" +
                engine.enabledProtocols.joinToString(","),
        )
    }

    private fun pairNew(host: Iap2UsbMuxHost): LockdownPairRecord {
        val paired = LockdownPairingClient(host).pair(
            label = LABEL,
            hostId = UUID.randomUUID().toString().uppercase(Locale.US),
            systemBuid = UUID.randomUUID().toString().uppercase(Locale.US),
            totalTimeoutMillis = PAIR_TIMEOUT_MILLIS,
            isCancelled = { closed.get() },
        )
        lockdownState.save(paired.pairRecord)
        report("iPhone Lockdown paired and saved")
        return paired.pairRecord
    }

    private fun isPairRejection(error: Throwable): Boolean {
        var cause: Throwable? = error
        while (cause != null) {
            if (cause is GeneralSecurityException) return true
            val message = cause.message.orEmpty()
            if (
                message.contains("InvalidHost", ignoreCase = true) ||
                message.contains("InvalidPair", ignoreCase = true) ||
                message.contains("PairRecord", ignoreCase = true) ||
                message.contains("HostID", ignoreCase = true)
            ) return true
            cause = cause.cause
        }
        return false
    }

    private fun awaitVpnService(): CarPlayVpnService? {
        vpnService?.let { return it }
        report("Binding CarPlay network service")
        bindVpn()
        return try {
            if (vpnLatch.await(VPN_CONNECT_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)) vpnService else null
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            null
        }
    }

    private fun bindVpn() {
        if (vpnBound || closed.get()) return
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
        vpnService = null
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

    private fun openNcm(device: UsbDevice): NcmUsbBridge {
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
        return NcmUsbBridge.open(connection, function)
    }

    private fun loadMfi(): MfiAuthenticator {
        mfiLease?.let { return it.client }
        return CrvMfiProvider.acquire(appContext, report).also {
            mfiLease = it
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

    private fun cleanupAfterFailure() {
        activeSession = null
        runCatching { bonjour?.close() }
        bonjour = null
        if (vpnAttached) {
            runCatching { vpnService?.detach() }
            vpnAttached = false
            ncm = null
        } else {
            runCatching { ncm?.close() }
            ncm = null
        }
        runCatching { csm?.close() }
        csm = null
        runCatching { mux?.close() }
        mux = null
        runCatching { wifiHotspot?.close() }
        wifiHotspot = null
        runCatching { mfiLease?.close() }
        mfiLease = null
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        cleanupAfterFailure()
        unbindVpn()
        sink.close()
        executor.shutdownNow()
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
        private const val KERNEL_NCM_RETRY_WAIT_MILLIS = 1_000L
    }
}
