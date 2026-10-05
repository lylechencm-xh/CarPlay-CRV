package com.shilapi.xcertplay

import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
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
import com.shilapi.xcertplay.transport.NcmFunctionDiscovery
import com.shilapi.xcertplay.transport.NcmUsbBridge
import java.io.Closeable
import java.security.GeneralSecurityException
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Android 4.4 CarPlay stack for the 2021 CR-V, supporting wired media and Wi-Fi handoff.
 *
 * USBMUX -> Lockdown pairing -> com.apple.carkit.service -> iAP2/MFi ->
 * USB NCM -> VpnService/AirPlay -> MediaCodec/AudioTrack.
 */
class CrvWiredCarPlayController(
    context: Context,
    private val usbManager: UsbManager,
    private val vpn: CarPlayVpnService,
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

    private val listener = object : AirPlaySessionListener {
        override fun onSessionActive(session: AirPlaySession) {
            activeSession = session
            report(vpn.diagnosticSummary())
            report("CarPlay active")
        }

        override fun onSessionEnded(session: AirPlaySession) {
            if (activeSession === session) activeSession = null
            report("CarPlay session ended")
        }

        override fun onTransportError(message: String) {
            report(vpn.diagnosticSummary())
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

        report(CrvMfiAssets.status(appContext))
        val mfi = loadMfi()
        report("MFi identity ready")

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
        report("iPhone Lockdown ready")
        val session = Iap2Session.open(
            underlying = carkit,
            traceContext = "crv-wired",
            onTrace = { line -> if (line.contains("FAIL") || line.contains("READY")) report(line) },
        )
        csm = session
        report("iAP2 carkit channel ready")

        if (mode == CrvConnectionMode.WIFI_HANDOFF) {
            runWirelessHandoff(session, mfi)
            return
        }

        val ncmBridge = openNcm(device)
        ncm = ncmBridge
        val hostMac = ncmBridge.hostMac ?: macBytes(deviceId)
        val airPlay = airPlayConfig(deviceId)

        when (
            val attached = vpn.attach(
                ncm = ncmBridge,
                linkLocal = LINK_LOCAL,
                hostMac = hostMac,
                config = airPlay,
                identity = identity,
                pairings = pairingStore,
                mfi = mfi,
                listener = listener,
                media = media,
            )
        ) {
            is CarPlayVpnService.AttachResult.Failed ->
                throw IphoneUsbException.DeviceUnavailable("NCM/AirPlay attach failed: ${attached.message}")
            else -> vpnAttached = true
        }

        val airPlayPort = vpn.boundPort()
            ?: throw IphoneUsbException.DeviceUnavailable("AirPlay listener did not bind")
        report("AirPlay listening on $LINK_LOCAL:$airPlayPort")

        val usbMuxInterfaceNumber = IphoneCarPlayConfiguration.usbMuxInterface(device)?.id
            ?: throw IphoneUsbException.Protocol("CarPlay USB layout exposes no USBMUX interface")
        report("CarPlay USBMUX interface=$usbMuxInterfaceNumber")

        val bluetoothMac = bluetoothTransportIdentifier()
        report("Wi-Fi Bluetooth transport id source=" + if (bluetoothMac == deviceId) "derived" else "adapter")

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
            ipv6Addresses = listOf(LINK_LOCAL),
            airPlayPort = airPlayPort,
            publicKey = identity.publicKeyHex,
            sourceVersion = SOURCE_VERSION,
            deviceIdentifier = hostMac.macString(),
        )

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

        val identification = Iap2IdentificationConfig(
            name = "Honda CR-V CarPlay",
            modelIdentifier = "CR-V-2021",
            manufacturer = "Honda",
            serialNumber = "CRV-${deviceId.replace(":", "")}",
            firmwareVersion = "1.0",
            hardwareVersion = "2021",
            carPlayUsbInterfaceNumber = 0,
            wireless = Iap2WirelessIdentification(
                bluetoothMac = bluetoothMac,
                ssid = hotspotInfo.ssid,
            ),
            locationInformationEnabled = false,
            vehicleStatusEnabled = false,
            vehicleSpeedEnabled = false,
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

    private fun openNcm(device: UsbDevice): NcmUsbBridge {
        val function = NcmFunctionDiscovery.find(device)
            ?: throw IphoneUsbException.Protocol("CarPlay USB layout exposes no CDC-NCM function")
        val connection = usbManager.openDevice(device)
            ?: throw IphoneUsbException.DeviceUnavailable("Could not open iPhone NCM USB connection")
        return NcmUsbBridge.open(connection, function)
    }

    private fun loadMfi(): MfiAuthenticator = CrvMfiAssets.load(appContext)

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
        opusAudioOutput = false,
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
            runCatching { vpn.detach() }
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
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        cleanupAfterFailure()
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
    }
}
