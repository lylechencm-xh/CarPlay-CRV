package com.shilapi.xcertplay.transport

import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbConstants
import android.util.Log
import java.io.Closeable
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicBoolean

/**
 * A blocking NCM data pipe that moves Ethernet frames as NTB16 blocks over bulk endpoints.
 *
 * The caller opens the USB connection while the CarPlay configuration is already active; this
 * bridge claims only the NCM control/data interfaces and owns the connection thereafter. All
 * calls may block and must run away from the Android main thread.
 */
enum class NcmClaimPolicy(internal val force: Boolean) {
    /** Claims only when no kernel driver owns the interface; never detaches cdc_ncm. */
    PRESERVE_KERNEL_DRIVER(false),

    /** Force-claims, detaching a bound kernel driver such as cdc_ncm from the interface. */
    DETACH_KERNEL_DRIVER(true),
    ;

    /** Whether a claim under this policy may detach a bound kernel driver. */
    val detachesKernelDriver: Boolean get() = force

    /**
     * The policy to retry with when a claim under this policy was refused because the interface is
     * busy. A forced claim has nothing left to escalate to, so it reports `null` and the caller must
     * surface the failure instead of detaching again.
     */
    val escalation: NcmClaimPolicy?
        get() = when (this) {
            PRESERVE_KERNEL_DRIVER -> DETACH_KERNEL_DRIVER
            DETACH_KERNEL_DRIVER -> null
        }
}

/** A timed-out bulk OUT has not accepted any bytes; the caller still owns the frame. */
enum class NcmWriteResult { SENT, NOT_READY }

class NcmUsbBridge internal constructor(
    private val connection: UsbDeviceConnection,
    private val outEndpoint: UsbEndpoint,
    private val inEndpoint: UsbEndpoint,
    private val statusEndpoint: UsbEndpoint?,
    private val claimedInterfaces: List<UsbInterface>,
    descriptorHostMac: ByteArray?,
    isDeviceAttached: (() -> Boolean)? = null,
) : Closeable {
    private val descriptorMac = descriptorHostMac?.copyOf()
    val hostMac: ByteArray? get() = descriptorMac?.copyOf()
    private val stateLock = Any()
    private val readLock = Any()
    private val writeLock = Any()
    private var closed = false
    private var failure: IphoneUsbException? = null
    private var sequence = 0
    private var loggedWriteTimeout = false
    private var consecutiveReadFailures = 0
    private var consecutiveWriteFailures = 0
    private val presenceGuard = NcmUsbPresenceGuard(isDeviceAttached)
    private val frames = ArrayDeque<ByteArray>()
    private var queuedBytes = 0
    private val wireDecoder = Ntb16WireDecoder()
    private val readBuffer = ByteArray(READ_CHUNK_BYTES)
    private val statusRunning = AtomicBoolean(statusEndpoint != null)
    private val statusThread = statusEndpoint?.let { endpoint ->
        Thread({ drainStatus(endpoint) }, "ncm-status-in").apply {
            isDaemon = true
            start()
        }
    }

    /** Wraps one Ethernet frame in one NTB16 block and writes it to bulk OUT. */
    fun send(frame: ByteArray, timeoutMillis: Int): NcmWriteResult = synchronized(writeLock) {
        checkOpen()
        require(timeoutMillis > 0) { "timeoutMillis must be positive" }
        val sequence = synchronized(stateLock) {
            checkOpenLocked()
            this.sequence.also { this.sequence = (this.sequence + 1) and 0xffff }
        }
        val block = Ntb16Codec.build(frame, sequence)
        // Android API17's four-argument bulkTransfer caps one transaction at 16 KiB.
        // Keep each intermediate transfer packet-aligned and use the API12 overload.
        var sent = 0
        while (sent < block.size) {
            val chunkSize = NcmLegacyUsbBulk.chunkSize(block.size - sent)
            val chunk = if (sent == 0 && chunkSize == block.size) {
                block
            } else {
                block.copyOfRange(sent, sent + chunkSize)
            }
            val transferred = try {
                connection.bulkTransfer(outEndpoint, chunk, chunk.size, timeoutMillis)
            } catch (error: RuntimeException) {
                throw failSession("NCM write failed", error)
            }
            if (transferred <= 0) {
                consecutiveWriteFailures++
                if (presenceGuard.disconnectedAfterFailures(consecutiveWriteFailures)) {
                    throw failSession("iPhone USB detached during NCM write")
                }
                // Before StartCarPlaySession the iPhone can NAK bulk OUT indefinitely.
                // Retry only if no part of this NTB has already reached the device.
                if (sent != 0) {
                    throw failSession("Partial NCM NTB write interrupted after " + sent + " bytes")
                }
                if (!loggedWriteTimeout) {
                    loggedWriteTimeout = true
                    Log.i(IphoneCarPlayConfiguration.TAG, "ncm bulk-out not ready; retaining bridge for retry")
                }
                return@synchronized NcmWriteResult.NOT_READY
            }
            if (transferred != chunkSize) {
                throw failSession(
                    "Partial NCM NTB write: " + transferred + " of " + chunkSize + " bytes",
                )
            }
            sent += transferred
        }
        consecutiveWriteFailures = 0
        presenceGuard.reset()
        if (loggedWriteTimeout) {
            loggedWriteTimeout = false
            Log.i(IphoneCarPlayConfiguration.TAG, "ncm bulk-out became ready")
        }
        NcmWriteResult.SENT
    }

    /**
     * Returns the next complete Ethernet frame, or null when [timeoutMillis] elapses without one.
     * USB reads may split or coalesce NTB blocks; this method reassembles whole blocks internally.
     */
    fun recv(timeoutMillis: Long): ByteArray? {
        require(timeoutMillis > 0) { "timeoutMillis must be positive" }
        synchronized(readLock) {
            checkOpen()
            if (frames.isNotEmpty()) return pollFrame()

            val deadline = System.nanoTime() + timeoutMillis * NANOS_PER_MILLISECOND
            while (true) {
                if (frames.isNotEmpty()) return pollFrame()
                val remainingNanos = deadline - System.nanoTime()
                if (remainingNanos <= 0) return null
                val chunkLength =
                    readChunk((remainingNanos + NANOS_PER_MILLISECOND - 1) / NANOS_PER_MILLISECOND)
                        ?: continue
                val decodedFrames = try {
                    wireDecoder.appendFrames(readBuffer, chunkLength)
                } catch (error: IllegalArgumentException) {
                    throw failSession("Invalid NCM NTB16 wire framing", error)
                }
                for (frame in decodedFrames) enqueueFrame(frame)
            }
        }
    }

    override fun close() {
        statusRunning.set(false)
        synchronized(stateLock) {
            if (closed) return
            closed = true
        }
        statusThread?.let { thread ->
            thread.interrupt()
            try {
                thread.join(STATUS_POLL_TIMEOUT_MILLIS + 250L)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
        for (usbInterface in claimedInterfaces.asReversed()) {
            try {
                connection.releaseInterface(usbInterface)
            } catch (_: RuntimeException) {
                // Best-effort release; the connection close below is authoritative.
            }
        }
        connection.close()
    }

    private fun drainStatus(endpoint: UsbEndpoint) {
        val buffer = ByteArray(endpoint.maxPacketSize.coerceAtLeast(64))
        var loggedFirst = false
        while (statusRunning.get()) {
            // Short synchronous polls keep this thread out of JNI critical sections most of the time;
            // notifications are rare, small interrupt packets that are only logged.
            val transferred = try {
                connection.bulkTransfer(endpoint, buffer, buffer.size, STATUS_POLL_TIMEOUT_MILLIS)
            } catch (_: RuntimeException) {
                return
            }
            if (transferred <= 0) {
                try {
                    Thread.sleep(STATUS_POLL_INTERVAL_MILLIS)
                } catch (_: InterruptedException) {
                    return
                }
                continue
            }
            if (!loggedFirst) {
                loggedFirst = true
                Log.i(
                    IphoneCarPlayConfiguration.TAG,
                    "ncm status notification bytes=$transferred data=${buffer.copyOf(transferred).hex(32)}",
                )
            }
        }
    }

    private fun ByteArray.hex(limit: Int): String =
        take(limit).joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }

    private fun enqueueFrame(frame: ByteArray) {
        if (frames.size >= MAX_QUEUED_FRAMES || queuedBytes + frame.size > MAX_QUEUED_BYTES) {
            throw failSession("NCM frame queue exceeded its bounds")
        }
        frames.addLast(frame)
        queuedBytes += frame.size
    }

    private fun pollFrame(): ByteArray {
        val frame = frames.removeFirst()
        queuedBytes -= frame.size
        return frame
    }

    private fun readChunk(timeoutMillis: Long): Int? {
        checkOpen()
        val timeout = timeoutMillis.coerceAtMost(Int.MAX_VALUE.toLong()).coerceAtLeast(1L).toInt()
        val transferred = try {
            connection.bulkTransfer(inEndpoint, readBuffer, readBuffer.size, timeout)
        } catch (error: RuntimeException) {
            throw failSession("NCM read failed", error)
        }
        if (transferred <= 0) {
            consecutiveReadFailures++
            if (presenceGuard.disconnectedAfterFailures(consecutiveReadFailures)) {
                throw failSession("iPhone USB detached during NCM read")
            }
            // Timeouts can be normal during setup or while the link is idle.
            if (consecutiveReadFailures % 256 == 0) {
                Log.i(IphoneCarPlayConfiguration.TAG, "ncm bulk-in idle attempts=" + consecutiveReadFailures)
            }
            return null
        }
        consecutiveReadFailures = 0
        presenceGuard.reset()
        return transferred
    }

    private fun failSession(message: String, cause: Throwable? = null): IphoneUsbException.DeviceUnavailable {
        val error = IphoneUsbException.DeviceUnavailable(message, cause)
        synchronized(stateLock) {
            if (failure == null) failure = error
        }
        return error
    }

    private fun checkOpen() {
        synchronized(stateLock) { checkOpenLocked() }
    }

    private fun checkOpenLocked() {
        failure?.let { throw it }
        if (closed) throw IphoneUsbException.DeviceUnavailable("NCM bridge is closed")
    }

    companion object {
        private const val READ_CHUNK_BYTES = NcmLegacyUsbBulk.MAX_TRANSACTION_BYTES
        private const val STATUS_POLL_TIMEOUT_MILLIS = 20
        private const val STATUS_POLL_INTERVAL_MILLIS = 500L
        private const val MAX_QUEUED_FRAMES = 256
        private const val MAX_QUEUED_BYTES = 1 shl 20
        private const val NANOS_PER_MILLISECOND = 1_000_000L

        /** Claims and activates the NCM control/data interfaces; owns the connection on success. */
        fun open(
            connection: UsbDeviceConnection,
            function: NcmFunctionDiscovery.NcmFunction,
            claimPolicy: NcmClaimPolicy,
            isDeviceAttached: (() -> Boolean)? = null,
        ): NcmUsbBridge {
            val forceClaim = claimPolicy.force
            val claimed = ArrayList<UsbInterface>(2)
            try {
                val descriptorHostMac = readNcmHostMac(connection, function)
                Log.i(
                    IphoneCarPlayConfiguration.TAG,
                    "ncm descriptor hostMac=${descriptorHostMac?.macString() ?: "unavailable"}",
                )
                // Apple's Ethernet function exposes control and data as alternate settings of the
                // same interface id, so it must be claimed once and switched with setInterface.
                val sameInterface = function.control.id == function.data.id
                val first = if (sameInterface) function.data else function.control
                val firstClaimed = connection.claimInterface(first, forceClaim)
                Log.i(
                    IphoneCarPlayConfiguration.TAG,
                    "claim iface=${first.id} class=${first.interfaceClass}" +
                        " subclass=${first.interfaceSubclass} proto=${first.interfaceProtocol}" +
                        " force=$forceClaim ok=$firstClaimed",
                )
                if (!firstClaimed) {
                    throw IphoneUsbException.InterfaceBusy(
                        "Android could not claim NCM interface ${first.id}; " +
                            "kernelDriverDetachAllowed=$forceClaim",
                    )
                }
                claimed.add(first)
                if (!sameInterface) {
                    val dataClaimed = connection.claimInterface(function.data, forceClaim)
                    Log.i(
                        IphoneCarPlayConfiguration.TAG,
                        "claim iface=${function.data.id}" +
                            " class=${function.data.interfaceClass}" +
                            " force=$forceClaim ok=$dataClaimed",
                    )
                    if (!dataClaimed) {
                        throw IphoneUsbException.InterfaceBusy(
                            "Android could not claim NCM data interface ${function.data.id} " +
                                "in USB configuration ${function.configurationValue ?: -1}; " +
                                "kernelDriverDetachAllowed=$forceClaim",
                        )
                    }
                    claimed.add(function.data)
                }
                if (android.os.Build.VERSION.SDK_INT < 21) {
                    val resetSelected = selectInterface(
                        connection,
                        function.data,
                        DATA_ALT_SETTING_DISABLED,
                    )
                    Log.i(
                        IphoneCarPlayConfiguration.TAG,
                        "setInterface iface=${function.data.id}/$DATA_ALT_SETTING_DISABLED ok=$resetSelected",
                    )
                    if (!resetSelected) {
                        Log.w(
                            IphoneCarPlayConfiguration.TAG,
                            "NCM data alt0 reset reported failure; continuing with selected data alt",
                        )
                    }
                }
                val altSelected = selectInterface(
                    connection,
                    function.data,
                    function.dataAlternateSetting,
                )
                Log.i(
                    IphoneCarPlayConfiguration.TAG,
                    "setInterface iface=${function.data.id}/${function.dataAlternateSetting} ok=$altSelected",
                )
                if (!altSelected) {
                    throw IphoneUsbException.DeviceUnavailable(
                        "Android could not select the NCM data alternate setting",
                    )
                }
                Log.i(
                    IphoneCarPlayConfiguration.TAG,
                    "ncm status endpoint=${function.statusIn?.address?.let { "0x${it.toString(16)}" } ?: "none"}",
                )
                return NcmUsbBridge(
                    connection,
                    function.bulkOut,
                    function.bulkIn,
                    function.statusIn,
                    claimed,
                    descriptorHostMac,
                    isDeviceAttached,
                )
            } catch (error: Throwable) {
                for (usbInterface in claimed.asReversed()) {
                    try {
                        connection.releaseInterface(usbInterface)
                    } catch (_: RuntimeException) {
                        // The connection close below is authoritative.
                    }
                }
                connection.close()
                if (error is IphoneUsbException) throw error
                throw IphoneUsbException.DeviceUnavailable("Android NCM open failed", error)
            }
        }

        /**
         * UsbDeviceConnection.setInterface() is API21. Android 4.2.2 can select an alternate
         * setting with the USB standard SET_INTERFACE control request on endpoint zero.
         */
        private fun selectInterface(
            connection: UsbDeviceConnection,
            usbInterface: UsbInterface,
            alternateSetting: Int = DATA_ALT_SETTING_FALLBACK,
        ): Boolean {
            if (android.os.Build.VERSION.SDK_INT >= 21) {
                return try {
                    val method = connection.javaClass.getMethod(
                        "setInterface",
                        UsbInterface::class.java,
                    )
                    method.invoke(connection, usbInterface) as? Boolean ?: false
                } catch (_: Exception) {
                    false
                }
            }
            return UsbLegacySelection.setInterface(
                connection,
                usbInterface.id,
                alternateSetting,
            )
        }

        private fun readNcmHostMac(
            connection: UsbDeviceConnection,
            function: NcmFunctionDiscovery.NcmFunction,
        ): ByteArray? {
            val configurationValue = function.configurationValue ?: return null
            val index = ethernetMacStringIndex(
                connection.rawDescriptors,
                configurationValue,
                function.control.id,
            ) ?: return null
            val buffer = ByteArray(256)
            val length = connection.controlTransfer(
                UsbConstants.USB_DIR_IN or UsbConstants.USB_TYPE_STANDARD,
                USB_REQUEST_GET_DESCRIPTOR,
                (USB_STRING_DESCRIPTOR_TYPE shl 8) or index,
                USB_ENGLISH_US,
                buffer,
                buffer.size,
                USB_CONTROL_TIMEOUT_MILLIS,
            )
            if (length < 4 || (buffer[1].toInt() and 0xff) != USB_STRING_DESCRIPTOR_TYPE) return null
            val descriptorLength = (buffer[0].toInt() and 0xff).coerceAtMost(length)
            if (descriptorLength < 4) return null
            val value = buffer.copyOfRange(2, descriptorLength).toString(Charsets.UTF_16LE)
            val hex = value.filter { it.digitToIntOrNull(16) != null }
            if (hex.length != 12) return null
            return ByteArray(6) { offset -> hex.substring(offset * 2, offset * 2 + 2).toInt(16).toByte() }
        }

        internal fun ethernetMacStringIndex(
            raw: ByteArray,
            configurationValue: Int,
            controlInterfaceId: Int,
        ): Int? {
            val control = UsbActiveConfiguration.interfaces(raw, configurationValue)
                .firstOrNull {
                    it.number == controlInterfaceId &&
                        it.alternateSetting == 0 &&
                        it.interfaceClass == NcmFunctionDiscovery.CONTROL_CLASS &&
                        it.interfaceSubclass == NcmFunctionDiscovery.CONTROL_SUBCLASS
                } ?: return null
            return control.extraDescriptors.firstNotNullOfOrNull { descriptor ->
                if (
                    descriptor.size >= 4 &&
                    (descriptor[1].toInt() and 0xff) == CDC_FUNCTIONAL_DESCRIPTOR_TYPE &&
                    (descriptor[2].toInt() and 0xff) == CDC_ETHERNET_SUBTYPE
                ) {
                    (descriptor[3].toInt() and 0xff).takeIf { it != 0 }
                } else {
                    null
                }
            }
        }

        private fun ByteArray.macString(): String =
            joinToString(":") { byte -> "%02x".format(byte.toInt() and 0xff) }

        private const val USB_REQUEST_GET_DESCRIPTOR = 0x06
        private const val USB_STRING_DESCRIPTOR_TYPE = 0x03
        private const val CDC_FUNCTIONAL_DESCRIPTOR_TYPE = 0x24
        private const val CDC_ETHERNET_SUBTYPE = 0x0f
        private const val USB_ENGLISH_US = 0x0409
        private const val USB_CONTROL_TIMEOUT_MILLIS = 1_000
        private const val DATA_ALT_SETTING_DISABLED = 0
        private const val DATA_ALT_SETTING_FALLBACK = 1
    }
}
