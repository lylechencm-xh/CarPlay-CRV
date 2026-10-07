package com.shilapi.xcertplay

import com.shilapi.xcertplay.airplay.AirPlaySession
import com.shilapi.xcertplay.iap2.session.Iap2Session
import com.shilapi.xcertplay.network.CarPlayVpnService
import com.shilapi.xcertplay.transport.Iap2UsbMuxHost
import com.shilapi.xcertplay.transport.NcmUsbBridge

/** Single owner for resources that must be released when a controller attempt ends. */
internal class CrvCarPlayResources {
    @Volatile var activeSession: AirPlaySession? = null
    @Volatile var mux: Iap2UsbMuxHost? = null
    @Volatile var csm: Iap2Session? = null
    @Volatile var ncm: NcmUsbBridge? = null
    @Volatile var vpnAttached: Boolean = false
    @Volatile var wifiHotspot: CrvApi19WirelessHotspot? = null
    @Volatile var bonjour: CrvApi19BonjourAdvertiser? = null
    @Volatile var vpnService: CarPlayVpnService? = null
    @Volatile var mfiLease: CrvMfiProvider.Lease? = null

    private val lock = Any()

    /** Atomically detaches ownership, then closes in dependency order. Repeated calls are no-ops. */
    fun releaseTransport() {
        val release = synchronized(lock) {
            val wasVpnAttached = vpnAttached
            vpnAttached = false
            Release(
                bonjour = bonjour.also { bonjour = null },
                vpnService = vpnService,
                vpnAttached = wasVpnAttached,
                ncm = ncm.also { ncm = null },
                csm = csm.also { csm = null },
                mux = mux.also { mux = null },
                wifiHotspot = wifiHotspot.also { wifiHotspot = null },
                mfiLease = mfiLease.also { mfiLease = null },
            ).also { activeSession = null }
        }

        runCatching { release.bonjour?.close() }
        if (release.vpnAttached && release.vpnService != null) {
            runCatching { release.vpnService.detach() }
        } else {
            runCatching { release.ncm?.close() }
        }
        runCatching { release.csm?.close() }
        runCatching { release.mux?.close() }
        runCatching { release.wifiHotspot?.close() }
        runCatching { release.mfiLease?.close() }
    }

    private data class Release(
        val bonjour: CrvApi19BonjourAdvertiser?,
        val vpnService: CarPlayVpnService?,
        val vpnAttached: Boolean,
        val ncm: NcmUsbBridge?,
        val csm: Iap2Session?,
        val mux: Iap2UsbMuxHost?,
        val wifiHotspot: CrvApi19WirelessHotspot?,
        val mfiLease: CrvMfiProvider.Lease?,
    )
}
