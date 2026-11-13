package io.github.hxcde.ambilight.service

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Process

/**
 * Ermittelt die App im Vordergrund über die Nutzungsstatistik.
 * Benötigt die Freigabe „Nutzungsdatenzugriff“ (am TV meist nur per ADB:
 * `adb shell appops set io.github.hxcde.ambilight GET_USAGE_STATS allow`).
 */
class ForegroundAppDetector(private val context: Context) {
    private val usm = context.getSystemService(UsageStatsManager::class.java)
    private var lastQuery = System.currentTimeMillis() - INITIAL_LOOKBACK_MS
    private var current: String? = null

    fun hasPermission(): Boolean = hasPermission(context)

    /** Liefert die aktuelle Vordergrund-App (Paketname) oder null, wenn unbekannt. */
    @Suppress("DEPRECATION")
    fun poll(): String? {
        val now = System.currentTimeMillis()
        val events = try {
            usm.queryEvents(lastQuery, now)
        } catch (e: SecurityException) {
            return null
        } ?: return current
        val e = UsageEvents.Event()
        while (events.hasNextEvent()) {
            events.getNextEvent(e)
            if (e.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND) current = e.packageName
        }
        lastQuery = now
        return current
    }

    fun label(pkg: String?): String? {
        pkg ?: return null
        return try {
            val pm = context.packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
        } catch (_: PackageManager.NameNotFoundException) {
            pkg
        }
    }

    companion object {
        private const val INITIAL_LOOKBACK_MS = 6 * 60 * 60 * 1000L

        @Suppress("DEPRECATION")
        fun hasPermission(context: Context): Boolean {
            val ops = context.getSystemService(AppOpsManager::class.java)
            val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
            } else {
                ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
            }
            return mode == AppOpsManager.MODE_ALLOWED
        }
    }
}
