package io.github.hxcde.ambilight.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import io.github.hxcde.ambilight.AppSettings

/**
 * Startet nach dem Booten die Bildschirmfreigabe, wenn Autostart aktiv ist.
 * Funktioniert ab Android 10 nur mit freigegebener Overlay-Berechtigung (siehe README, ADB-Befehle).
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED && action != "android.intent.action.QUICKBOOT_POWERON") return
        val settings = AppSettings(context)
        if (!settings.autostart || !settings.isConfigured) return
        try {
            context.startActivity(Intent(context, CaptureRequestActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) {
            Log.w("Ambilight", "Autostart blockiert", e)
        }
    }
}
