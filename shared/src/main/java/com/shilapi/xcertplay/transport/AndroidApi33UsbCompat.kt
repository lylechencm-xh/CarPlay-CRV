package com.shilapi.xcertplay.transport

import android.annotation.TargetApi
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager

/** API33-only USB broadcast helpers, isolated from classes loaded on Android 4.2.2. */
@TargetApi(33)
internal object AndroidApi33UsbCompat {
    fun registerNotExported(
        context: Context,
        receiver: BroadcastReceiver,
        filter: IntentFilter,
    ) {
        context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
    }

    fun usbDevice(intent: Intent): UsbDevice? =
        intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
}
