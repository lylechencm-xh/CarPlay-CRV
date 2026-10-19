package com.shilapi.xcertplay

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.view.KeyEvent
import com.shilapi.xcertplay.airplay.AirPlaySession
import com.shilapi.xcertplay.airplay.CarPlayMediaButton
import java.io.Closeable
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** API17 media receiver, also used when Honda delivers a key directly to the Activity. */
class CrvMediaButtonReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_MEDIA_BUTTON) return
        @Suppress("DEPRECATION")
        val event = intent.getParcelableExtra<KeyEvent>(Intent.EXTRA_KEY_EVENT) ?: return
        if (dispatch(event) && isOrderedBroadcast) abortBroadcast()
    }

    companion object {
        @Volatile internal var active: CrvMediaButtons? = null
        fun dispatch(event: KeyEvent): Boolean = active?.dispatch(event) ?: false
    }
}

internal class CrvMediaButtons(context: Context, private val report: (String) -> Unit) : Closeable {
    private val audio = context.applicationContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val component = ComponentName(context.applicationContext, CrvMediaButtonReceiver::class.java)
    private val policy = CrvMediaKeyPolicy()
    private val worker = ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
        ArrayBlockingQueue<Runnable>(16), { task -> Thread(task, "crv-media-keys").apply { isDaemon = true } })
    @Volatile private var session: AirPlaySession? = null
    @Volatile private var closed = false

    @Synchronized fun activate(next: AirPlaySession) {
        if (closed) return
        session = next
        synchronized(CrvMediaButtonReceiver::class.java) {
            CrvMediaButtonReceiver.active = this
            @Suppress("DEPRECATION")
            runCatching { audio.registerMediaButtonEventReceiver(component) }
                .onFailure { report("Media key registration failed: ${it.javaClass.simpleName}") }
        }
        report("Media key receiver active")
    }

    @Synchronized fun deactivate(ended: AirPlaySession? = null) {
        if (ended != null && session !== ended) return
        session = null
        worker.queue.clear()
        synchronized(CrvMediaButtonReceiver::class.java) {
            if (CrvMediaButtonReceiver.active === this) {
                CrvMediaButtonReceiver.active = null
                @Suppress("DEPRECATION")
                runCatching { audio.unregisterMediaButtonEventReceiver(component) }
            }
        }
    }

    fun dispatch(event: KeyEvent): Boolean {
        // Honda-specific raw keys must be observed before mapping; leave volume/DPAD untouched.
        val index = when (event.keyCode) {
            KeyEvent.KEYCODE_MEDIA_NEXT -> CarPlayMediaButton.NEXT
            KeyEvent.KEYCODE_MEDIA_PREVIOUS -> CarPlayMediaButton.PREVIOUS
            KeyEvent.KEYCODE_MEDIA_PLAY -> CarPlayMediaButton.PLAY
            KeyEvent.KEYCODE_MEDIA_PAUSE -> CarPlayMediaButton.PAUSE
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_HEADSETHOOK -> CarPlayMediaButton.PLAY_PAUSE
            else -> {
                if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                    report("Unmapped media key code=${event.keyCode} scanCode=${event.scanCode}")
                }
                return false
            }
        }
        val target = session ?: return false
        val keyCode = event.keyCode
        val scanCode = event.scanCode
        if (event.action == KeyEvent.ACTION_UP) return true
        if (event.action != KeyEvent.ACTION_DOWN || event.isCanceled) return false
        if (!policy.accept(event.deviceId, event.keyCode, event.downTime, event.repeatCount)) return true
        return try {
            worker.execute {
                if (!closed && session === target && CrvMediaButtonReceiver.active === this) {
                    val sent = runCatching { target.sendMedia(index) }.getOrDefault(false)
                    report("Media key code=$keyCode scanCode=$scanCode index=$index sent=$sent")
                }
            }
            true
        } catch (_: RejectedExecutionException) {
            report("Media key queue unavailable code=${event.keyCode}")
            false
        }
    }

    @Synchronized override fun close() {
        if (closed) return
        closed = true
        deactivate()
        worker.shutdownNow()
    }
}
