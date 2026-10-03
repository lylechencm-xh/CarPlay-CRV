package com.shilapi.xcertplay

import android.app.Activity
import android.content.pm.ActivityInfo
import android.graphics.Color
import android.graphics.SurfaceTexture
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Bundle
import android.view.Gravity
import android.view.TextureView
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import com.shilapi.xcertplay.transport.Iap2UsbSession
import com.shilapi.xcertplay.transport.IphoneUsbHost
import com.shilapi.xcertplay.transport.IphoneUsbMatcher
import java.io.Closeable
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * 2021 Honda CR-V / Android 4.4 wired CarPlay entry point.
 * No Compose, Media3, Android Auto or wireless dependencies.
 */
class CrvCarPlayActivity : Activity(), TextureView.SurfaceTextureListener {
    private lateinit var usbManager: UsbManager
    private lateinit var usbHost: IphoneUsbHost
    private lateinit var video: TextureView
    private lateinit var status: TextView
    private val io: ExecutorService = Executors.newSingleThreadExecutor()
    private var permissionReceiver: Closeable? = null
    private var attachReceiver: Closeable? = null
    private var usbSession: Iap2UsbSession? = null

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        video = TextureView(this).apply {
            surfaceTextureListener = this@CrvCarPlayActivity
            isOpaque = true
        }
        status = TextView(this).apply {
            text = "Connect iPhone by USB"
            setTextColor(Color.WHITE)
            setBackgroundColor(0x66000000)
            gravity = Gravity.CENTER
            setPadding(16, 10, 16, 10)
        }
        setContentView(FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            addView(video, FrameLayout.LayoutParams(-1, -1))
            addView(status, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
        })

        usbManager = getSystemService(USB_SERVICE) as UsbManager
        usbHost = IphoneUsbHost(this, usbManager, IphoneUsbMatcher.appleVendor())
        permissionReceiver = usbHost.registerPermissionReceiver { result ->
            when (result) {
                is IphoneUsbHost.PermissionResult.Granted -> beginUsb(result.device)
                is IphoneUsbHost.PermissionResult.Denied -> setStatus("USB permission denied")
            }
        }
        attachReceiver = usbHost.registerAttachReceiver { device ->
            setStatus("iPhone reattached")
            requestPermission(device)
        }

        val device = usbHost.discover().firstOrNull()
        if (device == null) setStatus("Connect iPhone by USB") else requestPermission(device)
    }

    private fun requestPermission(device: UsbDevice) {
        when (usbHost.requestPermission(device)) {
            is IphoneUsbHost.PermissionRequest.AlreadyGranted -> beginUsb(device)
            is IphoneUsbHost.PermissionRequest.Requested -> setStatus("Waiting for USB permission")
        }
    }

    private fun beginUsb(device: UsbDevice) {
        setStatus("Starting CarPlay USB mode")
        usbHost.requestCarPlayReenumerationAsync(device, io) { result ->
            when (result) {
                is IphoneUsbHost.TransitionResult.ReenumerationRequested ->
                    setStatus("Waiting for iPhone CarPlay USB mode")
                is IphoneUsbHost.TransitionResult.Failed ->
                    setStatus("USB setup failed: ${result.error.message ?: "unknown"}")
            }
        }
    }

    private fun openCarPlayUsb(device: UsbDevice) {
        usbHost.openIap2UsbSessionAsync(device, io) { result ->
            when (result) {
                is IphoneUsbHost.Iap2SessionResult.Connected -> {
                    usbSession?.close()
                    usbSession = result.session
                    setStatus("USBMUX connected")
                }
                is IphoneUsbHost.Iap2SessionResult.Failed ->
                    setStatus("USBMUX failed: ${result.error.message ?: "unknown"}")
            }
        }
    }

    private fun setStatus(message: String) {
        runOnUiThread { status.text = message }
    }

    override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) = Unit
    override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) = Unit
    override fun onSurfaceTextureUpdated(surface: SurfaceTexture) = Unit
    override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean = true

    override fun onDestroy() {
        permissionReceiver?.close()
        attachReceiver?.close()
        usbSession?.close()
        io.shutdownNow()
        super.onDestroy()
    }
}
