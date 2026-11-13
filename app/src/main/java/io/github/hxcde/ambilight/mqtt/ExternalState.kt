package io.github.hxcde.ambilight.mqtt

import io.github.hxcde.ambilight.engine.Playback

/**
 * TV-Status, den Home Assistant liefert, wenn der App die eigene Freigabe fehlt
 * (Blueprint „TV-Status übergeben“):
 * - `<app_topic>/external/app`: Paketname der App im Vordergrund (Attribut `app_id` der Android-TV-Remote-Integration)
 * - `<app_topic>/external/playback`: Zustand eines Media-Players (`playing`, `paused`, `idle`, `off`, …)
 */
object ExternalState {
    private val PACKAGE = Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+$")

    fun parseApp(payload: String): String? = payload.trim().takeIf { PACKAGE.matches(it) }

    fun parsePlayback(payload: String): Playback = when (payload.trim().lowercase()) {
        "playing", "buffering", "spielt" -> Playback.PLAYING
        "paused", "pausiert" -> Playback.PAUSED
        else -> Playback.NONE
    }
}
