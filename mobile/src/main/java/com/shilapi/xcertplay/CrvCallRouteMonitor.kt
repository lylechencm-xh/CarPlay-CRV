package com.shilapi.xcertplay

import android.app.ActivityManager
import android.bluetooth.BluetoothHeadset
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import java.io.Closeable

/** Passive OEM call diagnostics. Never disconnect HFP, start SCO, or steal the camera foreground. */
internal class CrvCallRouteMonitor(context: Context, private val report: (String) -> Unit) : Closeable {
    private val app = context.applicationContext
    private val audio = app.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent == null) return
            report("Audio OEM event=${intent.action} state=" +
                intent.getIntExtra(AudioManager.EXTRA_SCO_AUDIO_STATE,
                    intent.getIntExtra(BluetoothHeadset.EXTRA_STATE, -1)) + " " + snapshot())
        }
    }
    private var registered = false
    @android.annotation.SuppressLint("UnspecifiedRegisterReceiverFlag")
    fun start() {
        registered = runCatching {
            app.registerReceiver(receiver, IntentFilter().apply {
                addAction(AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED)
                addAction(BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED)
                addAction(BluetoothHeadset.ACTION_AUDIO_STATE_CHANGED)
            })
            true
        }.getOrDefault(false)
    }
    @Suppress("DEPRECATION")
    fun snapshot(): String = runCatching {
        "mode=${audio.mode} sco=${audio.isBluetoothScoOn} speaker=${audio.isSpeakerphoneOn}"
    }.getOrDefault("route=unavailable")

    @Suppress("DEPRECATION")
    fun foreground(reason: String) {
        val component = runCatching {
            val manager = app.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            manager.getRunningTasks(1).firstOrNull()?.topActivity?.flattenToShortString()
        }.getOrNull() ?: "unavailable"
        report("Audio OEM foreground reason=$reason component=$component ${snapshot()}")
    }
    override fun close() { if (registered) runCatching { app.unregisterReceiver(receiver) }; registered = false }
}
