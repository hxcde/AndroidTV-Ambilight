package io.github.hxcde.ambilight.service

import android.content.ComponentName
import android.content.Context
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import io.github.hxcde.ambilight.engine.Playback

/**
 * Beobachtet die Mediensitzungen (Netflix, Plex, YouTube, …) und meldet Wiedergabe/Pause.
 * Funktioniert auch bei DRM-geschützten Apps, weil nur der Zustand, nicht das Bild gelesen wird.
 */
class MediaWatcher(
    private val context: Context,
    private val handler: Handler,
    private val onChange: () -> Unit,
) {
    private val msm = context.getSystemService(MediaSessionManager::class.java)
    private val component = ComponentName(context, MediaListenerService::class.java)
    private var controllers: List<MediaController> = emptyList()
    private var started = false

    private val callback = object : MediaController.Callback() {
        override fun onPlaybackStateChanged(state: PlaybackState?) = onChange()
        override fun onSessionDestroyed() = onChange()
    }

    private val sessionsListener = MediaSessionManager.OnActiveSessionsChangedListener { list ->
        setControllers(list ?: emptyList())
        onChange()
    }

    /** Startet die Beobachtung, sobald die Freigabe erteilt ist. true = läuft. */
    fun ensureStarted(): Boolean {
        if (started) return true
        if (!hasPermission(context)) return false
        return try {
            msm.addOnActiveSessionsChangedListener(sessionsListener, component, handler)
            setControllers(msm.getActiveSessions(component))
            started = true
            onChange()
            true
        } catch (e: SecurityException) {
            Log.w("Ambilight", "Mediensitzungen nicht lesbar", e)
            false
        }
    }

    fun stop() {
        if (!started) return
        try {
            msm.removeOnActiveSessionsChangedListener(sessionsListener)
        } catch (_: Exception) {
        }
        controllers.forEach { it.unregisterCallback(callback) }
        controllers = emptyList()
        started = false
    }

    /**
     * Wiedergabezustand: der App im Vordergrund, wenn bekannt (eine pausierte Sitzung im Hintergrund
     * zählt dann nicht), sonst über alle Sitzungen.
     */
    fun state(foregroundPkg: String?): Playback {
        if (!started) return Playback.NONE
        val relevant = if (foregroundPkg != null) controllers.filter { it.packageName == foregroundPkg } else controllers
        val states = relevant.map { map(it.playbackState?.state) }
        return when {
            Playback.PLAYING in states -> Playback.PLAYING
            Playback.PAUSED in states -> Playback.PAUSED
            else -> Playback.NONE
        }
    }

    /** Hat diese App gerade eine Mediensitzung? */
    fun hasSession(pkg: String): Boolean = started && controllers.any { it.packageName == pkg }

    private fun setControllers(list: List<MediaController>) {
        controllers.forEach { it.unregisterCallback(callback) }
        controllers = list
        list.forEach { it.registerCallback(callback, handler) }
    }

    private fun map(state: Int?): Playback = when (state) {
        PlaybackState.STATE_PLAYING, PlaybackState.STATE_BUFFERING, PlaybackState.STATE_CONNECTING,
        PlaybackState.STATE_FAST_FORWARDING, PlaybackState.STATE_REWINDING,
        PlaybackState.STATE_SKIPPING_TO_NEXT, PlaybackState.STATE_SKIPPING_TO_PREVIOUS,
        PlaybackState.STATE_SKIPPING_TO_QUEUE_ITEM -> Playback.PLAYING
        PlaybackState.STATE_PAUSED -> Playback.PAUSED
        else -> Playback.NONE
    }

    companion object {
        fun hasPermission(context: Context): Boolean =
            NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)
    }
}
