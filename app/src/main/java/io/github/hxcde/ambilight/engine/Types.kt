package io.github.hxcde.ambilight.engine

/** Betriebsmodus, wie er in der App und in Home Assistant angezeigt wird. */
enum class Mode(val label: String) {
    STOPPED("Gestoppt"),
    ACTIVE("Aktiv"),
    PAUSED("Pausiert (Home Assistant)"),
    PROTECTED("Geschützter Inhalt"),
    APP_FALLBACK("Ersatzmodus (App-Profil)"),
    APP_RESTORE("Normales Licht (App-Profil)"),
    APP_OFF("Licht aus (App-Profil)"),
    STANDBY("Standby"),
}

/** Was mit den Lampen passiert, wenn das Ambilight sie freigibt (Standby, Stopp). */
enum class LightAction { RESTORE, OFF, NONE }

/** Verhalten pro App im Vordergrund. */
enum class AppAction { AMBILIGHT, FALLBACK, RESTORE, OFF }

/** Laufzeit-Statistik für Anzeige und Home Assistant. */
data class EngineStats(
    val framesPerSec: Float = 0f,
    val messagesPerSec: Float = 0f,
    val zigbeePerSec: Float = 0f,
    val latencyAvgMs: Int? = null,
    val latencyMaxMs: Int? = null,
)

/** Zuordnung App (Paketname) → Verhalten. Priorität: Licht aus > Normales Licht > Ersatzmodus. */
class AppProfiles(
    private val fallback: Set<String> = emptySet(),
    private val restore: Set<String> = emptySet(),
    private val off: Set<String> = emptySet(),
) {
    fun actionFor(pkg: String?): AppAction = when (pkg) {
        null -> AppAction.AMBILIGHT
        in off -> AppAction.OFF
        in restore -> AppAction.RESTORE
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
