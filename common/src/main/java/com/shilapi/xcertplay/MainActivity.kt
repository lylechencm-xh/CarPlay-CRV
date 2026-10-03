package com.shilapi.xcertplay

import android.app.Activity
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import com.shilapi.xcertplay.mfi.MfiProtocolMajorResult
import com.shilapi.xcertplay.mfi.MfiSelfCheck
import com.shilapi.xcertplay.mfi.MfiSelfCheckResult
import com.shilapi.xcertplay.transport.LinuxI2cTransport

/** API 19 compatible diagnostic entry point. Uses platform Views instead of Compose. */
class MainActivity : Activity() {
    private val executor: ExecutorService = Executors.newSingleThreadExecutor()
    private lateinit var statusView: TextView
    private lateinit var pathView: EditText
    private lateinit var runButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val density = resources.displayMetrics.density
        fun dp(value: Int) = (value * density + 0.5f).toInt()

        pathView = EditText(this).apply {
            setText("/dev/i2c-1")
            hint = "Linux I2C device"
            setSingleLine(true)
        }
        statusView = TextView(this).apply { text = "Idle" }
        runButton = Button(this).apply {
            text = "Run MFi self-check"
            setOnClickListener { runSelfCheck(pathView.text.toString()) }
        }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(24), dp(24), dp(24), dp(24))
            addView(TextView(this@MainActivity).apply { text = "Board I2C diagnostic" })
            addView(pathView)
            addView(runButton)
            addView(statusView)
            addView(TextView(this@MainActivity).apply {
                text = "CH341 requires deployment-specific VID/PID configuration."
            })
        })
    }

    override fun onDestroy() {
        executor.shutdownNow()
        super.onDestroy()
    }

    private fun runSelfCheck(devicePath: String) {
        runButton.isEnabled = false
        statusView.text = "Running..."
        executor.execute {
            val message = try {
                val result = LinuxI2cTransport.open(devicePath).use { MfiSelfCheck(it).run() }
                result.message()
            } catch (error: LinkageError) {
                "Failed: " + (error.message ?: "I2C native library is unavailable")
            } catch (error: Exception) {
                "Failed: " + (error.message ?: error.javaClass.simpleName)
            }
            runOnUiThread {
                if (!isFinishing) {
                    statusView.text = message
                    runButton.isEnabled = true
                }
            }
        }
    }
}

private fun MfiSelfCheckResult.message(): String {
    val found = chip ?: return if (discovery.interrupted) "MFi scan interrupted" else "Found: none"
    val major = when (val result = found.protocolMajor) {
        is MfiProtocolMajorResult.Value -> "%d".format(result.major)
        is MfiProtocolMajorResult.MfiFailure -> result.error.message ?: result.error.javaClass.simpleName
        is MfiProtocolMajorResult.TransportFailure -> result.error.message ?: result.error.javaClass.simpleName
    }
    return "Found: 0x%02X; device version: 0x%02X; protocol major (raw): %s".format(
        found.address7Bit, found.deviceVersion, major
    )
}
