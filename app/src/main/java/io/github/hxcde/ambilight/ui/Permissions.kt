package io.github.hxcde.ambilight.ui

import android.app.AppOpsManager
import android.content.Context
import android.content.Intent
import androidx.core.net.toUri
import android.os.Build
import android.os.Process
import android.provider.Settings
import io.github.hxcde.ambilight.R
import io.github.hxcde.ambilight.service.AmbilightState
import io.github.hxcde.ambilight.service.ForegroundAppDetector
import io.github.hxcde.ambilight.service.Status
import io.github.hxcde.ambilight.service.MediaWatcher

/** Optionale Freigaben der App mit Status, Nutzen, TV-Einstellung und ADB-Befehl. */
object Permissions {
    private const val PKG = "io.github.hxcde.ambilight"
    private const val LISTENER = "$PKG/$PKG.service.MediaListenerService"

    class Item(
        val key: String,
        val title: Int,
        val description: Int,
        val granted: Boolean,
        val adb: String,
        /** Einstellungsseite am TV, falls vorhanden; null = nur per ADB. */
        val settingsIntent: Intent?,
    )

    fun all(context: Context): List<Item> = listOf(
        Item(
            "capture", R.string.perm_capture_title, R.string.perm_capture_desc, hasProjectMedia(context),
            "appops set $PKG PROJECT_MEDIA allow", null,
        ),
        Item(
            "media", R.string.perm_media_title, R.string.perm_media_desc, MediaWatcher.hasPermission(context),
            "cmd notification allow_listener $LISTENER",
            resolvable(context, Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)),
        ),
        Item(
            "usage", R.string.perm_usage_title, R.string.perm_usage_desc, ForegroundAppDetector.hasPermission(context),
            "appops set $PKG GET_USAGE_STATS allow",
            resolvable(context, Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)),
        ),
        Item(
            "overlay", R.string.perm_overlay_title, R.string.perm_overlay_desc, Settings.canDrawOverlays(context),
            "appops set $PKG SYSTEM_ALERT_WINDOW allow",
            resolvable(context, Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, "package:$PKG".toUri())),
        ),
    )

    /** Wird die Freigabe gerade durch Daten aus Home Assistant ersetzt? */
    fun coveredByHa(item: Item, status: Status): Boolean = when (item.key) {
        "media" -> status.playbackFromHa
        "usage" -> status.appFromHa
        else -> false
    }

    /** Hinweis auf die Home-Assistant-Alternative zu dieser Freigabe, falls es eine gibt. */
    fun haHint(item: Item): Int? = when (item.key) {
        "media", "usage" -> R.string.perm_ha_hint
        "overlay" -> R.string.perm_ha_hint_start
        else -> null
    }

    fun missing(context: Context, status: Status = AmbilightState.status.value): Int =
        all(context).count { !it.granted && !coveredByHa(it, status) }

    /** Ein einziger ADB-Befehl für alle Freigaben. */
    fun combinedAdb(context: Context): String =
        "adb shell \"" + all(context).joinToString("; ") { it.adb } + "\""

    private fun resolvable(context: Context, intent: Intent): Intent? =
        intent.takeIf { it.resolveActivity(context.packageManager) != null }

    @Suppress("DEPRECATION")
    private fun hasProjectMedia(context: Context): Boolean = try {
        val ops = context.getSystemService(AppOpsManager::class.java)
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ops.unsafeCheckOpNoThrow("android:project_media", Process.myUid(), context.packageName)
        } else {
            ops.checkOpNoThrow("android:project_media", Process.myUid(), context.packageName)
        }
        mode == AppOpsManager.MODE_ALLOWED
    } catch (_: Exception) {
        false
    }
}
