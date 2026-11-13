package io.github.hxcde.ambilight.engine

/** Betriebsmodus, wie er in der App und in Home Assistant angezeigt wird. */
enum class Mode(val label: String) {
    STOPPED("Gestoppt"),
    ACTIVE("Aktiv"),
    PAUSED("Pausiert (Home Assistant)"),
    PROTECTED("Geschützter Inhalt"),
    APP_FALLBACK("Ersatzmodus (App-Profil)"),
    APP_STANDARD("Standardlicht (App-Profil)"),
    APP_OFF("Licht aus (App-Profil)"),
    STANDARD("Standardlicht"),
    PAUSE_LIGHT("Pausenlicht"),
    STANDBY("Standby"),
}

/** Was mit den Lampen passiert, wenn das Ambilight sie freigibt (Standby, Stopp). */
enum class LightAction { RESTORE, STANDARD, OFF, NONE }

/** Verhalten pro App im Vordergrund. */
enum class AppAction { AMBILIGHT, FALLBACK, STANDARD, OFF }

/** Wiedergabezustand der Mediensitzung (Netflix, Plex, YouTube, …). */
enum class Playback(val label: String) { NONE("–"), PLAYING("Spielt"), PAUSED("Pausiert") }

/** Verhalten bei pausierter Wiedergabe. */
enum class PauseAction { AMBILIGHT, PAUSE_LIGHT, STANDARD }

/** Verhalten beim Einschalten des TV bzw. Start des Ambilights. */
enum class WakeAction { AMBILIGHT, STANDARD_UNTIL_PLAY }

/** Feste Lichtszene: Farbe pro Lampe und gemeinsame Helligkeit. */
data class LightScene(
    val left: io.github.hxcde.ambilight.light.FixedColor,
    val right: io.github.hxcde.ambilight.light.FixedColor,
    val brightness: Int,
)

/** Laufzeit-Statistik für Anzeige und Home Assistant. */
data class EngineStats(
    val framesPerSec: Float = 0f,
    val messagesPerSec: Float = 0f,
    val zigbeePerSec: Float = 0f,
    val latencyAvgMs: Int? = null,
    val latencyMaxMs: Int? = null,
)

/** Zuordnung App (Paketname) → Verhalten. Priorität: Licht aus > Standardlicht > Ersatzmodus. */
class AppProfiles(
    private val fallback: Set<String> = emptySet(),
    private val standard: Set<String> = emptySet(),
    private val off: Set<String> = emptySet(),
) {
    fun actionFor(pkg: String?): AppAction = when (pkg) {
        null -> AppAction.AMBILIGHT
        in off -> AppAction.OFF
        in standard -> AppAction.STANDARD
        in fallback -> AppAction.FALLBACK
        else -> AppAction.AMBILIGHT
    }

    companion object {
        /** Bekannte Streaming-Apps mit Kopierschutz (liefern bei der Aufnahme nur Schwarz). */
        val DEFAULT_FALLBACK = setOf(
            "com.netflix.ninja",
            "com.amazon.amazonvideo.livingroom",
            "com.disney.disneyplus",
            "com.apple.atve.androidtv.appletv",
            "com.dazn",
        )
    }
}
