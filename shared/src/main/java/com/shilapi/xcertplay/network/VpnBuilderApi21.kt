package com.shilapi.xcertplay.network

import android.annotation.TargetApi
import android.net.VpnService

/** API21-only VpnService.Builder features, isolated from the API17-loaded service class. */
@TargetApi(21)
internal object VpnBuilderApi21 {
    fun configure(builder: VpnService.Builder, packageName: String) {
        builder.setBlocking(true)
        builder.addAllowedApplication(packageName)
    }
}
