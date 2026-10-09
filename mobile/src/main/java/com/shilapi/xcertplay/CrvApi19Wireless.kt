package com.shilapi.xcertplay

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiConfiguration
import android.net.wifi.WifiManager
import java.io.Closeable
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.lang.reflect.Method
import java.security.SecureRandom
import java.util.Collections

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

    fun start(timeoutMillis: Long = 15_000L): Info {
        if (closed || started || apRequested) {
            throw IllegalStateException("Wi-Fi hotspot is closed or already started")
        }

        // Never replace an active vehicle hotspot or change its saved configuration without
        // being able to restore it. These methods are vendor APIs on Android 4.2.2.
        val apState = wifi.javaClass.methods.firstOrNull {
            it.name == "getWifiApState" && it.parameterTypes.isEmpty()
        }?.invoke(wifi) as? Int
            ?: throw UnsupportedOperationException("Honda Wi-Fi hotspot state is unavailable")
        if (apState != WIFI_AP_STATE_DISABLED) {
            throw IllegalStateException("Turn off the existing vehicle hotspot before Wi-Fi handoff")
        }
        val original = wifi.javaClass.methods.firstOrNull {
            it.name == "getWifiApConfiguration" && it.parameterTypes.isEmpty()
        }?.invoke(wifi) as? WifiConfiguration
            ?: throw UnsupportedOperationException("Honda hotspot configuration cannot be backed up")
        val setter = wifi.javaClass.methods.firstOrNull {
            it.name == "setWifiApConfiguration" && it.parameterTypes.size == 1
        } ?: throw UnsupportedOperationException("Honda hotspot configuration cannot be restored")
        val enable = wifi.javaClass.methods.firstOrNull {
            it.name == "setWifiApEnabled" && it.parameterTypes.size == 2
        } ?: throw UnsupportedOperationException("Honda Wi-Fi framework does not expose hotspot control")
        originalConfiguration = original
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
                restoreWifi = wifi.isWifiEnabled
                if (restoreWifi) {
                    if (!wifi.setWifiEnabled(false)) {
                        throw IllegalStateException("Could not disable Wi-Fi station for hotspot")
                    }
                    Thread.sleep(500L)
                }
                apRequested = true
                val enabled = enable.invoke(wifi, config, true) as? Boolean ?: true
                if (!enabled) throw IllegalStateException("CR-V Wi-Fi hotspot request was rejected")
                started = true
            }
            report("Wi-Fi hotspot requested")

            val deadline = android.os.SystemClock.elapsedRealtime() + timeoutMillis
            while (android.os.SystemClock.elapsedRealtime() < deadline) {
                if (closed) throw IllegalStateException("Wi-Fi hotspot was closed")
                val state = wifi.javaClass.methods.firstOrNull {
                    it.name == "getWifiApState" && it.parameterTypes.isEmpty()
                }?.invoke(wifi) as? Int
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
        val wasRequested = apRequested
        apRequested = false
        started = false
        if (wasRequested) {
            runCatching {
                val method = wifi.javaClass.methods.firstOrNull {
                    it.name == "setWifiApEnabled" && it.parameterTypes.size == 2
                }
                method?.invoke(wifi, null, false)
            }.onFailure { report("Wi-Fi hotspot stop failed") }
            val saved = originalConfiguration
            val setter = restoreConfiguration
            if (saved != null && setter != null) {
                runCatching { setter.invoke(wifi, saved) }
                    .onFailure { report("Original hotspot configuration restore failed") }
            }
        }
        originalConfiguration = null
        restoreConfiguration = null
        if (restoreWifi) {
            runCatching { wifi.isWifiEnabled = true }
                .onFailure { report("Wi-Fi station restore failed") }
        }
        restoreWifi = false
        if (wasRequested) report("Wi-Fi hotspot stopped")
    }

    private fun randomHex(bytes: Int): String =
        ByteArray(bytes).also { SecureRandom().nextBytes(it) }
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private companion object {
        const val WIFI_AP_STATE_DISABLED = 11
        const val WIFI_AP_STATE_ENABLED = 13
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
    WIRED,
    WIFI_HANDOFF,
}
