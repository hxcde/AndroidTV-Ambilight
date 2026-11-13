package io.github.hxcde.ambilight.mqtt

import io.github.hxcde.ambilight.AppSettings
import io.github.hxcde.ambilight.Option
import io.github.hxcde.ambilight.Options
import kotlin.math.roundToInt

/** Einstellung, die in Home Assistant als Entität erscheint und von dort geändert werden kann. */
sealed class HaEntity(val id: String, val name: String, val prefKey: String, val component: String, val icon: String)

class HaNumber(
    id: String, name: String, prefKey: String, icon: String,
    val min: Int, val max: Int, val step: Int, val default: Int, val unit: String? = null,
) : HaEntity(id, name, prefKey, "number", icon) {
    fun parse(payload: String): Int? = payload.trim().toDoubleOrNull()?.roundToInt()?.coerceIn(min, max)
}

class HaSelect(
    id: String, name: String, prefKey: String, icon: String,
    val options: List<Option>, val default: String,
) : HaEntity(id, name, prefKey, "select", icon) {
    fun parse(payload: String): String? = Options.valueOf(options, payload.trim())
    fun label(value: String?): String = Options.labelOf(options, value) ?: Options.labelOf(options, default)!!
}

class HaSwitch(
    id: String, name: String, prefKey: String, icon: String, val default: Boolean,
) : HaEntity(id, name, prefKey, "switch", icon) {
    fun parse(payload: String): Boolean? = when (payload.trim().uppercase()) {
        "ON" -> true
        "OFF" -> false
        else -> null
    }
}

/** Alle per Home Assistant steuerbaren Einstellungen. */
object HaEntities {
    val ALL: List<HaEntity> = listOf(
        HaNumber("brightness_max", "Helligkeit maximal", AppSettings.K_MAX_BRIGHTNESS, "mdi:brightness-7", 1, 254, 1, 254),
        HaNumber("brightness_min", "Helligkeit minimal", AppSettings.K_MIN_BRIGHTNESS, "mdi:brightness-4", 1, 254, 1, 10),
        HaNumber("update_rate", "Updates pro Sekunde", AppSettings.K_RATE, "mdi:speedometer", 1, 10, 1, 5),
        HaNumber("smoothing", "Glättung", AppSettings.K_SMOOTHING, "mdi:blur", 0, 90, 5, 30, "%"),
        HaNumber("saturation", "Sättigung", AppSettings.K_SATURATION, "mdi:palette", 50, 250, 5, 130, "%"),
        HaNumber("black_brightness", "Ersatzmodus Helligkeit", AppSettings.K_BLACK_BRIGHTNESS, "mdi:brightness-5", 1, 254, 1, 80),
        HaSelect("black_mode", "Ersatzmodus", AppSettings.K_BLACK_MODE, "mdi:television-off", Options.BLACK_MODE, "COLOR"),
        HaSelect("black_color_left", "Ersatzfarbe links", AppSettings.K_BLACK_COLOR_LEFT, "mdi:palette-swatch", Options.FIXED_COLOR, "warm"),
        HaSelect("black_color_right", "Ersatzfarbe rechts", AppSettings.K_BLACK_COLOR_RIGHT, "mdi:palette-swatch", Options.FIXED_COLOR, "warm"),
        HaSelect("brightness_mode", "Helligkeitsmodus", AppSettings.K_BRIGHTNESS_MODE, "mdi:brightness-auto", Options.BRIGHTNESS_MODE, "DYNAMIC"),
        HaSelect("standby_action", "Bei TV-Standby", AppSettings.K_STANDBY_ACTION, "mdi:power-sleep", Options.LIGHT_ACTION, "RESTORE"),
        HaSelect("stop_action", "Beim Stoppen", AppSettings.K_STOP_ACTION, "mdi:stop-circle-outline", Options.LIGHT_ACTION, "RESTORE"),
        HaSwitch("scene_cut", "Szenenwechsel sofort", AppSettings.K_SCENE_CUT, "mdi:movie-open-outline", true),
        HaSwitch("app_detection", "App-Erkennung", AppSettings.K_APP_DETECTION, "mdi:apps", true),
    )

    fun byId(id: String): HaEntity? = ALL.firstOrNull { it.id == id }
    fun byPrefKey(key: String?): HaEntity? = ALL.firstOrNull { it.prefKey == key }
}
