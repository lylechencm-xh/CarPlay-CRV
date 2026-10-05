package com.shilapi.xcertplay.hud

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** No-op in the CR-V Android 4.2.2 build; BYD starter bridge support is excluded. */
class StarterBridgeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) = Unit
}
