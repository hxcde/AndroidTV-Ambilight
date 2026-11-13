package io.github.hxcde.ambilight

/** Ein Auswahlwert: gespeicherter Wert + Anzeigetext (auch für Home-Assistant-Selects). */
data class Option(val value: String, val label: String)

/** Auswahllisten, gemeinsam genutzt von Einstellungen und Home Assistant. */
object Options {
    val BLACK_MODE = listOf(
        Option("COLOR", "Feste Farbe (pro Lampe)"),
        Option("HOLD", "Letzte Farbe halten"),
        Option("OFF", "Licht aus"),
        Option("NORMAL", "Nichts tun (Lampen werden dunkel)"),
    )

    val FIXED_COLOR = listOf(
        Option("warm", "Warmweiß (2700 K)"),
        Option("neutral", "Neutralweiß (4000 K)"),
        Option("cold", "Kaltweiß (6500 K)"),
        Option("#FF0000", "Rot"),
        Option("#FF6A00", "Orange"),
        Option("#FFC800", "Gelb"),
        Option("#00FF00", "Grün"),
        Option("#00FFC8", "Türkis"),
        Option("#0000FF", "Blau"),
        Option("#8000FF", "Lila"),
        Option("#FF00A0", "Pink"),
        Option("custom", "Eigene Farbe …"),
    )

    val LIGHT_ACTION = listOf(
        Option("OFF", "Licht aus"),
        Option("RESTORE", "Vorheriger Zustand"),
        Option("STANDARD", "Standardlicht"),
        Option("NONE", "Unverändert lassen"),
    )

    val PAUSE_ACTION = listOf(
        Option("PAUSE_LIGHT", "Pausenlicht"),
        Option("STANDARD", "Standardlicht"),
        Option("AMBILIGHT", "Ambilight läuft weiter"),
    )

    val WAKE_ACTION = listOf(
        Option("STANDARD_UNTIL_PLAY", "Standardlicht, bis etwas abgespielt wird"),
        Option("AMBILIGHT", "Sofort Ambilight"),
    )

    /** Farben für Standard- und Pausenlicht (ohne „Eigene Farbe“). */
    val SCENE_COLOR = FIXED_COLOR.filter { it.value != "custom" }

    val BRIGHTNESS_MODE = listOf(
        Option("DYNAMIC", "Dynamisch (folgt dem Bild)"),
        Option("FIXED", "Fest (nur Farbe senden, halbe Zigbee-Last)"),
    )

    fun labelOf(options: List<Option>, value: String?): String? = options.firstOrNull { it.value == value }?.label
    fun valueOf(options: List<Option>, label: String): String? =
        options.firstOrNull { it.label == label }?.value ?: options.firstOrNull { it.value.equals(label, ignoreCase = true) }?.value
}
