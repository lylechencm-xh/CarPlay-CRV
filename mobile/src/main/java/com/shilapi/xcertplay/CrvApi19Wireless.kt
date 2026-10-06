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
    private var started = false
    private var restoreWifi = false

    fun start(timeoutMillis: Long = 15_000L): Info {
        if (started) throw IllegalStateException("Wi-Fi hotspot already started")

        val interfacesBeforeStart = currentInterfaceNames()
        val config = WifiConfiguration().apply {
            SSID = SSID
            preSharedKey = PASSPHRASE
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

        restoreWifi = wifi.isWifiEnabled
        if (restoreWifi) {
            runCatching { wifi.isWifiEnabled = false }
            Thread.sleep(500L)
        }

        val method = wifi.javaClass.methods.firstOrNull {
            it.name == "setWifiApEnabled" && it.parameterTypes.size == 2
        } ?: throw UnsupportedOperationException(
            "Honda Wi-Fi framework does not expose setWifiApEnabled",
        )

        val enabled = try {
            method.invoke(wifi, config, true) as? Boolean ?: true
        } catch (error: Exception) {
            throw IllegalStateException("Could not enable CR-V Wi-Fi hotspot", error)
        }
        if (!enabled) throw IllegalStateException("CR-V Wi-Fi hotspot request was rejected")
        started = true
        report("Wi-Fi hotspot requested")

        val deadline = android.os.SystemClock.elapsedRealtime() + timeoutMillis
        while (android.os.SystemClock.elapsedRealtime() < deadline) {
            findApEndpoint(interfacesBeforeStart)?.let { endpoint ->
                val channel = currentApChannel(config)
                report(
                    "Wi-Fi hotspot ready interface=${endpoint.first} " +
                        "address=${endpoint.second.hostAddress} channel=$channel",
                )
                return Info(
                    ssid = SSID,
                    passphrase = PASSPHRASE,
                    channel = channel,
                    hostAddress = endpoint.second,
                    interfaceName = endpoint.first,
                )
            }
            Thread.sleep(300L)
        }

        close()
        throw IllegalStateException("Wi-Fi hotspot started but no AP address appeared")
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
                isNew -> 4
                else -> 5
            }
        }
        for (iface in preferred) {
            val usable = runCatching { iface.isUp }.getOrDefault(true)
            if (!usable) continue
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

    override fun close() {
        if (!started) return
        started = false
        runCatching {
            val method = wifi.javaClass.methods.firstOrNull {
                it.name == "setWifiApEnabled" && it.parameterTypes.size == 2
            }
            method?.invoke(wifi, null, false)
        }
        if (restoreWifi) {
            runCatching { wifi.isWifiEnabled = true }
        }
        report("Wi-Fi hotspot stopped")
    }

    private companion object {
        const val SSID = "CRV-CarPlay"
        const val PASSPHRASE = "CarPlay2021"
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

    private val listener = object : NsdManager.RegistrationListener {
        override fun onServiceRegistered(serviceInfo: NsdServiceInfo) {
            registered = true
            report("Wi-Fi AirPlay advertised")
        }

        override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
            report("Wi-Fi AirPlay advertisement failed code=$errorCode")
        }

        override fun onServiceUnregistered(serviceInfo: NsdServiceInfo) {
            registered = false
        }

        override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
            report("Wi-Fi AirPlay unregistration failed code=$errorCode")
        }
    }

    fun start() {
        val info = NsdServiceInfo().apply {
            this.serviceName = this@CrvApi19BonjourAdvertiser.serviceName
            serviceType = "_airplay._tcp."
            port = this@CrvApi19BonjourAdvertiser.port
        }
        nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener)
    }

    override fun close() {
        if (registered) {
            runCatching { nsd.unregisterService(listener) }
        }
        registered = false
    }
}

enum class CrvConnectionMode {
    WIRED,
    WIFI_HANDOFF,
}
