package io.github.hxcde.ambilight.mqtt

import io.github.hxcde.ambilight.AppSettings
import io.github.hxcde.ambilight.Option
import io.github.hxcde.ambilight.Options
import io.github.hxcde.ambilight.light.FixedColor
import org.json.JSONException
import org.json.JSONObject
import kotlin.math.roundToInt

/** Einstellung, die in Home Assistant als Entität erscheint und von dort geändert werden kann. */
sealed class HaEntity(val id: String, val name: String, val prefKey: String, val component: String, val icon: String) {
    /** Alle Einstellungsschlüssel, deren Änderung den Zustand dieser Entität ändert. */
    open val keys: Set<String> get() = setOf(prefKey)
}

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

/**
 * Feste Lichtfarbe (Ersatz-, Standard- oder Pausenlicht) als Licht-Entität mit Farbrad.
 * Ist immer „an“: Sie beschreibt eine Einstellung, keine echte Lampe.
 *
 * @param colorKey Auswahlwert (Vorgabe wie „warm“ oder `#RRGGBB`)
 * @param customKey bei den Ersatzfarben: separates Hex-Feld, das mit `custom` im [colorKey] aktiv wird
 */
class HaLight(
    id: String, name: String, icon: String,
    val colorKey: String,
    val customKey: String?,
    val brightnessKey: String,
    val brightnessDefault: Int,
) : HaEntity(id, name, colorKey, "light", icon) {
    override val keys: Set<String> get() = setOfNotNull(colorKey, customKey, brightnessKey)

    data class Command(val hex: String?, val brightness: Int?)

    /** Zustand für Home Assistant (JSON-Schema mit RGB-Farbmodus). */
    fun state(color: FixedColor, brightness: Int): String {
        val rgb = color.previewArgb
        return """{"state":"ON","color_mode":"rgb","brightness":$brightness,""" +
            """"color":{"r":${(rgb shr 16) and 0xFF},"g":${(rgb shr 8) and 0xFF},"b":${rgb and 0xFF}}}"""
    }

    companion object {
        /** Befehl von Home Assistant, z. B. `{"state":"ON","color":{"r":128,"g":0,"b":255},"brightness":120}`. */
        fun parse(json: String): Command? = try {
            val o = JSONObject(json)
            val c = o.optJSONObject("color")
            val hex = if (c != null && c.has("r") && c.has("g") && c.has("b")) {
                "#%02X%02X%02X".format(
                    c.optInt("r").coerceIn(0, 255), c.optInt("g").coerceIn(0, 255), c.optInt("b").coerceIn(0, 255),
                ).takeIf { FixedColor.isValidHex(it) }
            } else {
                null
            }
            val brightness = if (o.has("brightness")) o.optInt("brightness").coerceIn(1, 254) else null
            Command(hex, brightness)
        } catch (_: JSONException) {
            null
        }
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
        HaSelect("black_mode", "Ersatzmodus", AppSettings.K_BLACK_MODE, "mdi:television-off", Options.BLACK_MODE, "COLOR"),
        HaSelect("brightness_mode", "Helligkeitsmodus", AppSettings.K_BRIGHTNESS_MODE, "mdi:brightness-auto", Options.BRIGHTNESS_MODE, "DYNAMIC"),
        HaSelect("standby_action", "Bei TV-Standby", AppSettings.K_STANDBY_ACTION, "mdi:power-sleep", Options.LIGHT_ACTION, "OFF"),
        HaSelect("wake_action", "Beim Einschalten", AppSettings.K_WAKE_ACTION, "mdi:power", Options.WAKE_ACTION, "STANDARD_UNTIL_PLAY"),
        HaSelect("pause_action", "Bei Pause", AppSettings.K_PAUSE_ACTION, "mdi:pause-circle-outline", Options.PAUSE_ACTION, "PAUSE_LIGHT"),
        HaSelect("stop_action", "Beim Stoppen", AppSettings.K_STOP_ACTION, "mdi:stop-circle-outline", Options.LIGHT_ACTION, "RESTORE"),
        HaSwitch("scene_cut", "Szenenwechsel sofort", AppSettings.K_SCENE_CUT, "mdi:movie-open-outline", true),
        HaSwitch("app_detection", "App-Erkennung", AppSettings.K_APP_DETECTION, "mdi:apps", true),
        HaLight(
            "black_light_left", "Ersatzlicht links", "mdi:television-off",
            AppSettings.K_BLACK_COLOR_LEFT, AppSettings.K_BLACK_COLOR_LEFT_CUSTOM, AppSettings.K_BLACK_BRIGHTNESS, 80,
        ),
        HaLight(
            "black_light_right", "Ersatzlicht rechts", "mdi:television-off",
            AppSettings.K_BLACK_COLOR_RIGHT, AppSettings.K_BLACK_COLOR_RIGHT_CUSTOM, AppSettings.K_BLACK_BRIGHTNESS, 80,
        ),
        HaLight("standard_light_left", "Standardlicht links", "mdi:lamp", AppSettings.K_STANDARD_COLOR_LEFT, null, AppSettings.K_STANDARD_BRIGHTNESS, 150),
        HaLight("standard_light_right", "Standardlicht rechts", "mdi:lamp", AppSettings.K_STANDARD_COLOR_RIGHT, null, AppSettings.K_STANDARD_BRIGHTNESS, 150),
        HaLight("pause_light", "Pausenlicht", "mdi:pause-circle", AppSettings.K_PAUSE_COLOR, null, AppSettings.K_PAUSE_BRIGHTNESS, 230),
    )

    /** Entitäten älterer Versionen, deren Discovery-Config entfernt wird ("<component>/<id>"). */
    val LEGACY = listOf(
        "number/black_brightness", "select/black_color_left", "select/black_color_right",
        "select/standard_color_left", "select/standard_color_right", "number/standard_brightness",
        "select/pause_color", "number/pause_brightness",
    )

    fun byId(id: String): HaEntity? = ALL.firstOrNull { it.id == id }

    /** Entitäten, deren Zustand sich durch eine Änderung an [key] ändert. */
    fun affectedBy(key: String?): List<HaEntity> = ALL.filter { key != null && key in it.keys }
}
