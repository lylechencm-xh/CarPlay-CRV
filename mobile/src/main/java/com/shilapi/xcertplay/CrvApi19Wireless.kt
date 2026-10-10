package com.shilapi.xcertplay

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiConfiguration
import android.net.wifi.WifiManager
import android.os.Parcel
import java.io.Closeable
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.lang.reflect.Method
import java.security.SecureRandom
import java.util.Collections
import java.util.BitSet

/**
 * Android 4.2.2 / API17-compatible local Wi-Fi AP used by wireless CarPlay handoff.
 *
 * Jelly Bean predates LocalOnlyHotspot, so the AP is started through the vendor/framework
 * setWifiApEnabled(WifiConfiguration, boolean) method when the head unit exposes it.
 */
internal class CrvApi19WirelessHotspot(
    context: Context,
    private val report: (String) -> Unit,
) : Closeable {
    data class Info(
        val ssid: String,
        val passphrase: String,
        val channel: Int,
        val hostAddress: InetAddress,
        val interfaceName: String,
    )

    private val wifi = context.applicationContext
        .getSystemService(Context.WIFI_SERVICE) as WifiManager
    private val lock = Any()
    @Volatile private var closed = false
    private var started = false
    private var restoreWifi = false
    private var apRequested = false
    private var originalConfiguration: WifiConfiguration? = null
    private var restoreConfiguration: Method? = null
    private val ssid = "CRV-CarPlay-" + randomHex(3)
    private val passphrase = randomHex(16)

    // The public WifiConfiguration copy constructor needs API30. Parcelable cloning uses
    // API1 methods and preserves the fields implemented by the OEM's own configuration class.
    private fun copyConfiguration(original: WifiConfiguration): WifiConfiguration {
        val parcel = Parcel.obtain()
        return try {
            parcel.writeParcelable(original, 0)
            parcel.setDataPosition(0)
            val copy = checkNotNull(parcel.readParcelable<WifiConfiguration>(WifiConfiguration::class.java.classLoader))
            check(configurationSnapshot(copy) == configurationSnapshot(original)) {
                "Honda hotspot backup did not preserve its configuration"
            }
            copy
        } finally {
            parcel.recycle()
        }
    }

    fun start(timeoutMillis: Long = 15_000L): Info {
        // Retain a failed restoration across controller replacement. Do not replace its backup.
        pendingRestoration?.let { pending ->
            pending.close()
            check(pendingRestoration == null) { "Previous hotspot restoration is incomplete" }
        }
        if (closed || started || apRequested) {
            throw IllegalStateException("Wi-Fi hotspot is closed or already started")
        }

        // Never replace an active vehicle hotspot or change its saved configuration without
        // being able to restore it. These methods are vendor APIs on Android 4.2.2.
        var apState = readApState()
        if (apState == CrvHotspotState.DISABLING) {
            apState = awaitApDisabled()
        }
        CrvHotspotState.startError(apState)?.let { throw IllegalStateException(it) }
        val original = readApConfiguration()
            ?: throw UnsupportedOperationException("Honda hotspot configuration cannot be backed up")
        check(configurationSnapshot(original).canVerify) {
            "Honda hotspot backup contains a hidden password; restoration cannot be verified"
        }
        val setter = wifi.javaClass.methods.firstOrNull {
            it.name == "setWifiApConfiguration" && it.parameterTypes.size == 1
        } ?: throw UnsupportedOperationException("Honda hotspot configuration cannot be restored")
        val enable = wifi.javaClass.methods.firstOrNull {
            it.name == "setWifiApEnabled" && it.parameterTypes.size == 2
        } ?: throw UnsupportedOperationException("Honda Wi-Fi framework does not expose hotspot control")
        originalConfiguration = copyConfiguration(original)
        restoreConfiguration = setter

        val interfacesBeforeStart = currentInterfaceNames()
        val config = WifiConfiguration().apply {
            SSID = ssid
            preSharedKey = passphrase
            hiddenSSID = false
            allowedAuthAlgorithms.clear()
            allowedGroupCiphers.clear()
            allowedPairwiseCiphers.clear()
            allowedProtocols.clear()
            allowedKeyManagement.clear()
            allowedKeyManagement.set(WifiConfiguration.KeyMgmt.WPA_PSK)
            allowedAuthAlgorithms.set(WifiConfiguration.AuthAlgorithm.OPEN)
            allowedPairwiseCiphers.set(WifiConfiguration.PairwiseCipher.CCMP)
            allowedGroupCiphers.set(WifiConfiguration.GroupCipher.CCMP)
            allowedProtocols.set(WifiConfiguration.Protocol.RSN)
        }

        try {
            synchronized(lock) {
                if (closed) throw IllegalStateException("Wi-Fi hotspot was closed")
                val stationState = readStationState()
                check(stationState != WifiManager.WIFI_STATE_UNKNOWN) {
                    "Wi-Fi station state is unknown; cannot safely restore it"
                }
                restoreWifi = stationState == WifiManager.WIFI_STATE_ENABLED ||
                    stationState == WifiManager.WIFI_STATE_ENABLING
                if (stationState != WifiManager.WIFI_STATE_DISABLED) {
                    if (!wifi.setWifiEnabled(false)) {
                        throw IllegalStateException("Could not disable Wi-Fi station for hotspot")
                    }
                    check(awaitCrvCondition(STATION_TIMEOUT_MS) {
                        check(!closed) { "Wi-Fi hotspot was closed" }
                        readStationState() == WifiManager.WIFI_STATE_DISABLED
                    }) { "Wi-Fi station disable was not confirmed" }
                }
                apRequested = true
                CrvHotspotState.requireAccepted(enable.invoke(wifi, config, true), "CR-V Wi-Fi hotspot request")
                started = true
            }
            report("Wi-Fi hotspot requested")

            val deadline = android.os.SystemClock.elapsedRealtime() + timeoutMillis
            while (android.os.SystemClock.elapsedRealtime() < deadline) {
                if (closed) throw IllegalStateException("Wi-Fi hotspot was closed")
                val state = readApState()
                if (state == CrvHotspotState.FAILED) {
                    throw IllegalStateException("Honda Wi-Fi hotspot failed during startup")
                }
                if (state == WIFI_AP_STATE_ENABLED) {
                    findApEndpoint(interfacesBeforeStart)?.let { endpoint ->
                        val channel = currentApChannel(config)
                        report(
                            "Wi-Fi hotspot ready interface=${endpoint.first} " +
                                "address=${endpoint.second.hostAddress} channel=$channel",
                        )
                        return Info(
                            ssid = ssid,
                            passphrase = passphrase,
                            channel = channel,
                            hostAddress = endpoint.second,
                            interfaceName = endpoint.first,
                        )
                    }
                }
                Thread.sleep(300L)
            }
            throw IllegalStateException("Wi-Fi hotspot started but no AP address appeared")
        } catch (error: Throwable) {
            close()
            throw error
        }
    }

    private fun currentInterfaceNames(): Set<String> = try {
        Collections.list(NetworkInterface.getNetworkInterfaces())
            .mapNotNull { it.name }
            .toSet()
    } catch (_: Exception) {
        emptySet()
    }

    private fun findApEndpoint(beforeStart: Set<String>): Pair<String, InetAddress>? {
        val interfaces = try {
            Collections.list(NetworkInterface.getNetworkInterfaces())
        } catch (_: Exception) {
            return null
        }

        val preferred = interfaces.sortedBy { iface ->
            val name = iface.name.orEmpty().lowercase()
            val isNew = iface.name !in beforeStart
            when {
                isNew && (name.startsWith("ap") || name.startsWith("wlan")) -> 0
                name.startsWith("ap") -> 1
                name.startsWith("wlan") -> 2
                name.startsWith("wifi") -> 3
                else -> 5
            }
        }
        for (iface in preferred) {
            val usable = runCatching { iface.isUp }.getOrDefault(true)
            if (!usable) continue
            val name = iface.name.orEmpty().lowercase()
            if (!(name.startsWith("ap") || name.startsWith("wlan") || name.startsWith("wifi"))) continue
            val address = Collections.list(iface.inetAddresses)
                .filterIsInstance<Inet4Address>()
                .firstOrNull {
                    !it.isLoopbackAddress &&
                        !it.isLinkLocalAddress &&
                        !it.isAnyLocalAddress &&
                        !it.isMulticastAddress &&
                        it.isSiteLocalAddress
                }
            if (address != null) return iface.name.orEmpty() to address
        }
        return null
    }

    private fun currentApChannel(fallback: WifiConfiguration): Int {
        val active = runCatching {
            val method = wifi.javaClass.methods.firstOrNull {
                it.name == "getWifiApConfiguration" && it.parameterTypes.isEmpty()
            }
            method?.invoke(wifi) as? WifiConfiguration
        }.getOrNull() ?: fallback

        for (fieldName in listOf("apChannel", "channel")) {
            val value = runCatching {
                val field = active.javaClass.getDeclaredField(fieldName)
                field.isAccessible = true
                field.getInt(active)
            }.getOrNull()
            if (value != null && value in 1..196) return value
        }
        report("Wi-Fi hotspot channel unavailable from Honda framework")
        return 0
    }

    override fun close() = synchronized(lock) {
        closed = true
        try {
            if (apRequested) {
                if (readApState() != CrvHotspotState.DISABLED) {
                    val method = wifi.javaClass.methods.firstOrNull {
                        it.name == "setWifiApEnabled" && it.parameterTypes.size == 2
                    } ?: throw UnsupportedOperationException("Honda hotspot stop is unavailable")
                    CrvHotspotState.requireAccepted(method.invoke(wifi, null, false), "Wi-Fi hotspot stop")
                    report("Wi-Fi hotspot stop requested")
                    check(awaitApDisabled() == CrvHotspotState.DISABLED) { "Wi-Fi hotspot stop was not confirmed" }
                }
                apRequested = false
                started = false
                report("Wi-Fi hotspot stopped")
            }
            val saved = originalConfiguration
            val setter = restoreConfiguration
            if (saved != null && setter != null) {
                CrvHotspotState.requireAccepted(setter.invoke(wifi, saved), "Original hotspot configuration restore")
                val expected = configurationSnapshot(saved)
                check(awaitCrvCondition(CONFIGURATION_TIMEOUT_MS) {
                    readApConfiguration()?.let { expected.matches(configurationSnapshot(it)) } == true
                }) { "Original hotspot configuration restore did not pass readback verification" }
                originalConfiguration = null
                restoreConfiguration = null
                report("Original hotspot configuration restored")
            }
            if (restoreWifi) {
                if (!wifi.isWifiEnabled) {
                    CrvHotspotState.requireAccepted(wifi.setWifiEnabled(true), "Wi-Fi station restore")
                }
                check(awaitCrvCondition(STATION_TIMEOUT_MS) {
                    readStationState() == WifiManager.WIFI_STATE_ENABLED
                }) { "Wi-Fi station restore was not confirmed" }
                restoreWifi = false
                report("Wi-Fi station restored")
            }
            if (pendingRestoration === this) pendingRestoration = null
        } catch (error: Exception) {
            pendingRestoration = this
            report("Wi-Fi restoration incomplete error=${error.javaClass.simpleName} " +
                "message=${error.message.orEmpty().take(200)}; backup retained for retry")
            if (error is InterruptedException) Thread.currentThread().interrupt()
        }
    }

    private fun readApState(): Int {
        val state = wifi.javaClass.methods.firstOrNull {
            it.name == "getWifiApState" && it.parameterTypes.isEmpty()
        }?.invoke(wifi) as? Int
            ?: throw UnsupportedOperationException("Honda Wi-Fi hotspot state is unavailable")
        if (state != lastReportedApState) {
            lastReportedApState = state
            report("Wi-Fi hotspot state=$state")
        }
        return state
    }

    private fun awaitApDisabled(): Int {
        var state = readApState()
        awaitCrvCondition(AP_STOP_TIMEOUT_MS) {
            state = readApState()
            state == CrvHotspotState.DISABLED
        }
        return state
    }

    private fun readApConfiguration(): WifiConfiguration? = wifi.javaClass.methods.firstOrNull {
        it.name == "getWifiApConfiguration" && it.parameterTypes.isEmpty()
    }?.invoke(wifi) as? WifiConfiguration

    private fun configurationSnapshot(config: WifiConfiguration) = CrvHotspotConfiguration(
        config.SSID, config.hiddenSSID, config.preSharedKey,
        config.allowedKeyManagement.cardinality() > 0 && !config.allowedKeyManagement.get(WifiConfiguration.KeyMgmt.NONE),
        listOf(config.allowedKeyManagement, config.allowedAuthAlgorithms, config.allowedProtocols,
            config.allowedPairwiseCiphers, config.allowedGroupCiphers).map { it.clone() as BitSet },
    )

    private fun readStationState(): Int {
        val state = wifi.wifiState
        if (state != lastReportedStationState) {
            lastReportedStationState = state
            report("Wi-Fi station state=$state")
        }
        return state
    }

    private var lastReportedApState: Int? = null
    private var lastReportedStationState: Int? = null

    private fun randomHex(bytes: Int): String =
        ByteArray(bytes).also { SecureRandom().nextBytes(it) }
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private companion object {
        const val WIFI_AP_STATE_ENABLED = 13
        // Cover the framework's five-second tether shutdown path, with driver margin.
        const val AP_STOP_TIMEOUT_MS = 8_000L
        const val STATION_TIMEOUT_MS = 10_000L
        const val CONFIGURATION_TIMEOUT_MS = 3_000L
        @Volatile var pendingRestoration: CrvApi19WirelessHotspot? = null
    }
}

/** Minimal API19-safe DNS-SD advertisement for the AirPlay listener. */
internal class CrvApi19BonjourAdvertiser(
    context: Context,
    private val serviceName: String,
    private val port: Int,
    private val report: (String) -> Unit,
) : Closeable {
    private val nsd = context.applicationContext
        .getSystemService(Context.NSD_SERVICE) as NsdManager
    private var registered = false
    private var registrationRequested = false
    private var closed = false

    private val listener = object : NsdManager.RegistrationListener {
        override fun onServiceRegistered(serviceInfo: NsdServiceInfo) {
            synchronized(this@CrvApi19BonjourAdvertiser) {
                registered = true
                if (closed) {
                    runCatching { nsd.unregisterService(this) }
                        .onFailure { report("Wi-Fi AirPlay late unregister failed") }
                }
            }
            report("Wi-Fi AirPlay advertised")
        }

        override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
            synchronized(this@CrvApi19BonjourAdvertiser) {
                registrationRequested = false
                registered = false
            }
            report("Wi-Fi AirPlay advertisement failed code=$errorCode")
        }

        override fun onServiceUnregistered(serviceInfo: NsdServiceInfo) {
            registered = false
        }

        override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
            report("Wi-Fi AirPlay unregistration failed code=$errorCode")
        }
    }

    @Synchronized fun start() {
        check(!closed && !registrationRequested) { "AirPlay advertiser already started or closed" }
        val info = NsdServiceInfo().apply {
            this.serviceName = this@CrvApi19BonjourAdvertiser.serviceName
            serviceType = "_airplay._tcp."
            port = this@CrvApi19BonjourAdvertiser.port
        }
        registrationRequested = true
        try {
            nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener)
        } catch (error: Throwable) {
            registrationRequested = false
            throw error
        }
    }

    @Synchronized override fun close() {
        closed = true
        if (registrationRequested) {
            runCatching { nsd.unregisterService(listener) }
                .onFailure { report("Wi-Fi AirPlay unregister pending or failed") }
        }
        registrationRequested = false
        registered = false
    }
}

enum class CrvConnectionMode {
    AUTO,
    WIRED,
    WIFI_HANDOFF,
}
