package com.shilapi.xcertplay.hud

import android.app.Activity
import android.os.Bundle
import android.widget.TextView

/**
 * CR-V build placeholder. The upstream BYD HUD demo is intentionally unavailable because the
 * Android 4.2.2 CR-V runtime excludes BYD/HUD integrations.
 */
class StandaloneHudDemoActivity : Activity() {
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        setContentView(TextView(this).apply {
            text = "HUD demo is not available in the CR-V Android 4.2.2 build."
        })
    }
}
