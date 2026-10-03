package com.shilapi.xcertplay

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.MotionEvent
import android.view.TextureView
import android.widget.FrameLayout
import android.widget.TextView

/**
 * Android 4.4 entry point.
 *
 * Deliberately uses only platform UI classes. The wired CarPlay controller/media session is
 * attached by the legacy bootstrap while modern wireless/settings UI remains out of the API19 path.
 */
class LegacyCarPlayActivity : Activity() {
    private lateinit var video: TextureView
    private lateinit var status: TextView

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        video = TextureView(this).apply {
            isOpaque = true
            setOnTouchListener { _, event -> forwardTouch(event) }
        }
        status = TextView(this).apply {
            text = "Connect iPhone by USB"
            setTextColor(Color.WHITE)
            setBackgroundColor(0x66000000)
            gravity = Gravity.CENTER
            setPadding(16, 8, 16, 8)
        }
        setContentView(FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            addView(video, FrameLayout.LayoutParams(-1, -1))
            addView(status, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
        })
    }

    fun videoView(): TextureView = video

    fun setConnectionStatus(message: String) {
        runOnUiThread { status.text = message }
    }

    private fun forwardTouch(event: MotionEvent): Boolean {
        // Hook for the AirPlay HID session. Keeping this platform-only makes the rendering
        // surface usable on API19 before the modern host/settings layer is ported.
        return event.actionMasked == MotionEvent.ACTION_DOWN ||
            event.actionMasked == MotionEvent.ACTION_MOVE ||
            event.actionMasked == MotionEvent.ACTION_UP
    }
}
