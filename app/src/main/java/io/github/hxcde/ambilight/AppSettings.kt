package io.github.hxcde.ambilight

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import androidx.preference.PreferenceManager
import io.github.hxcde.ambilight.light.FixedColor
import io.github.hxcde.ambilight.light.PipelineConfig
import io.github.hxcde.ambilight.mqtt.MqttConfig
import java.security.SecureRandom

/** Was bei komplett schwarzem Bild (typisch: DRM-geschützte Apps) passiert. */
enum class BlackMode { COLOR, HOLD, OFF, NORMAL }

/** Typisierter Zugriff auf die Einstellungen (Schlüssel siehe res/xml/preferences.xml). */
class AppSettings(private val prefs: SharedPreferences) {

    constructor(context: Context) : this(PreferenceManager.getDefaultSharedPreferences(context))

    val mqttHost: String get() = str(K_HOST, "")
    val mqttPort: Int get() = str(K_PORT, "1883").toIntOrNull()?.takeIf { it in 1..65535 } ?: 1883
    val mqttTls: Boolean get() = prefs.getBoolean(K_TLS, false)
    val mqttUser: String get() = str(K_USER, "")
    val mqttPassword: String get() = prefs.getString(K_PASSWORD, "") ?: ""

    val baseTopic: String get() = str(K_BASE_TOPIC, "zigbee2mqtt").trim('/')
    val lightLeft: String get() = str(K_LIGHT_LEFT, "")
    val lightRight: String get() = str(K_LIGHT_RIGHT, "")

    val updateRate: Int get() = prefs.getInt(K_RATE, 5).coerceIn(1, 10)
    /** Übergangszeit in Sekunden; 0 in den Einstellungen = automatisch (1 / Rate). */
    val transitionSec: Double
        get() {
            val ms = prefs.getInt(K_TRANSITION, 0)
            return if (ms <= 0) 1.0 / updateRate else ms / 1000.0
        }
    val zoneFraction: Float get() = prefs.getInt(K_ZONE, 30).coerceIn(5, 50) / 100f

    val pipeline: PipelineConfig
        get() = PipelineConfig(
            smoothing = prefs.getInt(K_SMOOTHING, 30).coerceIn(0, 90) / 100f,
            saturation = prefs.getInt(K_SATURATION, 130).coerceIn(50, 250) / 100f,
            minBrightness = prefs.getInt(K_MIN_BRIGHTNESS, 10).coerceIn(1, 254),
            maxBrightness = prefs.getInt(K_MAX_BRIGHTNESS, 254).coerceIn(1, 254),
        )

    val blackMode: BlackMode
        get() = runCatching { BlackMode.valueOf(str(K_BLACK_MODE, BlackMode.COLOR.name)) }.getOrDefault(BlackMode.COLOR)
    val blackBrightness: Int get() = prefs.getInt(K_BLACK_BRIGHTNESS, 80).coerceIn(1, 254)
    val blackColorLeft: FixedColor get() = fixedColor(K_BLACK_COLOR_LEFT, K_BLACK_COLOR_LEFT_CUSTOM)
    val blackColorRight: FixedColor get() = fixedColor(K_BLACK_COLOR_RIGHT, K_BLACK_COLOR_RIGHT_CUSTOM)

    private fun fixedColor(listKey: String, customKey: String): FixedColor {
        val choice = str(listKey, FixedColor.WARM)
        val value = if (choice == FixedColor.CUSTOM) str(customKey, "") else choice
        return FixedColor.parse(value) ?: FixedColor.DEFAULT
    }
    val blackDelayMs: Long get() = 2_000L

    val offOnStandby: Boolean get() = prefs.getBoolean(K_OFF_STANDBY, true)
    val offOnStop: Boolean get() = prefs.getBoolean(K_OFF_STOP, true)
    val autostart: Boolean get() = prefs.getBoolean(K_AUTOSTART, false)

    val haDiscovery: Boolean get() = prefs.getBoolean(K_HA_DISCOVERY, true)
    val haPrefix: String get() = str(K_HA_PREFIX, "homeassistant").trim('/')
    val appTopic: String get() = str(K_APP_TOPIC, "tv-ambilight").trim('/')

    /** Stabile, zufällige Client-ID je Installation (auch für Home-Assistant-unique_id). */
    val clientId: String
        get() {
            prefs.getString(K_CLIENT_ID, null)?.let { return it }
            val bytes = ByteArray(3).also { SecureRandom().nextBytes(it) }
            val id = "tv-ambilight-" + bytes.joinToString("") { "%02x".format(it) }
            prefs.edit { putString(K_CLIENT_ID, id) }
            return id
        }

    val isConfigured: Boolean get() = mqttHost.isNotEmpty() && lightLeft.isNotEmpty() && lightRight.isNotEmpty()

    fun mqttConfig(will: io.github.hxcde.ambilight.mqtt.MqttPackets.Will? = null) = MqttConfig(
        host = mqttHost,
        port = mqttPort,
        tls = mqttTls,
        username = mqttUser.ifEmpty { null },
        password = mqttPassword.ifEmpty { null },
        clientId = clientId,
        will = will,
    )

    private fun str(key: String, def: String): String = (prefs.getString(key, def) ?: def).trim().ifEmpty { def }

    companion object {
        const val K_HOST = "mqtt_host"
        const val K_PORT = "mqtt_port"
        const val K_TLS = "mqtt_tls"
        const val K_USER = "mqtt_user"
        const val K_PASSWORD = "mqtt_password"
        const val K_BASE_TOPIC = "z2m_base_topic"
        const val K_LIGHT_LEFT = "light_left"
        const val K_LIGHT_RIGHT = "light_right"
        const val K_RATE = "update_rate"
        const val K_TRANSITION = "transition_ms"
        const val K_ZONE = "zone_width"
        const val K_SMOOTHING = "smoothing"
        const val K_SATURATION = "saturation"
        const val K_MIN_BRIGHTNESS = "brightness_min"
        const val K_MAX_BRIGHTNESS = "brightness_max"
        const val K_BLACK_MODE = "black_mode"
        const val K_BLACK_BRIGHTNESS = "black_brightness"
        const val K_BLACK_COLOR_LEFT = "black_color_left"
        const val K_BLACK_COLOR_LEFT_CUSTOM = "black_color_left_custom"
        const val K_BLACK_COLOR_RIGHT = "black_color_right"
        const val K_BLACK_COLOR_RIGHT_CUSTOM = "black_color_right_custom"
        const val K_OFF_STANDBY = "off_on_standby"
        const val K_OFF_STOP = "off_on_stop"
        const val K_AUTOSTART = "autostart"
        const val K_HA_DISCOVERY = "ha_discovery"
        const val K_HA_PREFIX = "ha_prefix"
        const val K_APP_TOPIC = "app_topic"
        const val K_CLIENT_ID = "client_id"
    }
}
