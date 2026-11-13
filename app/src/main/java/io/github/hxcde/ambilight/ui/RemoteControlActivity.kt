package io.github.hxcde.ambilight.ui

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.util.Log
import io.github.hxcde.ambilight.engine.Mode
import io.github.hxcde.ambilight.service.AmbilightService
import io.github.hxcde.ambilight.service.AmbilightState

/**
 * Unsichtbarer Einstieg für Start/Stopp von außen, z. B. aus Home Assistant:
 * - Android-TV-Remote-Integration: `remote.turn_on` mit `activity: tvambilight://start` bzw. `tvambilight://stop`
 * - ADB: `am start -a android.intent.action.VIEW -d tvambilight://start`
 *
 * Gestartet wird immer über die normale Bildschirmfreigabe (ohne `PROJECT_MEDIA`-Freigabe mit Dialog am TV).
 */
class RemoteControlActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val command = intent?.data?.host?.lowercase() ?: when (intent?.action) {
            ACTION_START -> "start"
            ACTION_STOP -> "stop"
            else -> null
        }
        val running = AmbilightState.status.value.mode != Mode.STOPPED
        Log.i("Ambilight", "Fernsteuerung: $command (läuft: $running)")
        when (command) {
            "start" -> if (!running) startActivity(Intent(this, CaptureRequestActivity::class.java))
            "stop" -> AmbilightService.stop(this)
        }
        finish()
    }

    companion object {
        const val ACTION_START = "io.github.hxcde.ambilight.action.START"
        const val ACTION_STOP = "io.github.hxcde.ambilight.action.STOP"
    }
}
