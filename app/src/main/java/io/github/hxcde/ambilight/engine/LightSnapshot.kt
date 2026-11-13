package io.github.hxcde.ambilight.engine

import org.json.JSONException
import org.json.JSONObject
import java.util.Locale

/** Zustand einer Lampe vor dem Ambilight, aus der Zigbee2MQTT-Statusmeldung gelesen. */
data class LightSnapshot(
    val on: Boolean,
    val brightness: Int? = null,
    val colorMode: String? = null,
    val x: Double? = null,
    val y: Double? = null,
    val colorTemp: Int? = null,
) {
    /** Zigbee2MQTT-Befehl, der diesen Zustand wiederherstellt. */
    fun payload(transitionSec: Double): String {
        val t = String.format(Locale.ROOT, "%.2f", transitionSec)
        if (!on) return """{"state":"OFF","transition":$t}"""
        val parts = ArrayList<String>(4)
        parts += "\"state\":\"ON\""
        brightness?.let { parts += "\"brightness\":$it" }
        val useTemp = colorTemp != null && (colorMode == "color_temp" || x == null || y == null)
        if (useTemp) {
            parts += "\"color_temp\":$colorTemp"
        } else if (x != null && y != null) {
            parts += String.format(Locale.ROOT, "\"color\":{\"x\":%.4f,\"y\":%.4f}", x, y)
        }
        parts += "\"transition\":$t"
        return parts.joinToString(",", "{", "}")
    }

    companion object {
        /** Liest eine Statusmeldung von `zigbee2mqtt/<Lampe>`; null, wenn kein Schaltzustand enthalten ist. */
        fun parse(json: String): LightSnapshot? = try {
            val o = JSONObject(json)
            when (o.optString("state", "").uppercase()) {
                "ON", "OFF" -> {
                    val color = o.optJSONObject("color")
                    LightSnapshot(
                        on = o.optString("state").equals("ON", ignoreCase = true),
                        brightness = if (o.has("brightness") && !o.isNull("brightness")) o.optInt("brightness").coerceIn(1, 254) else null,
                        colorMode = o.optString("color_mode", "").ifEmpty { null },
                        x = color?.optDouble("x")?.takeUnless { it.isNaN() },
                        y = color?.optDouble("y")?.takeUnless { it.isNaN() },
                        colorTemp = if (o.has("color_temp") && !o.isNull("color_temp")) o.optInt("color_temp") else null,
                    )
                }
                else -> null
            }
        } catch (_: JSONException) {
            null
        }

        /** Anfrage an Zigbee2MQTT (`…/get`), den aktuellen Zustand zu melden. */
        const val GET_PAYLOAD = """{"state":"","brightness":"","color":"","color_temp":""}"""
    }
}
