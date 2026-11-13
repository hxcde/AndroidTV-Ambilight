package io.github.hxcde.ambilight.mqtt

/**
 * Topics und MQTT-Discovery-Payloads für Home Assistant:
 * Schalter (Ambilight an/pausiert), Button (beenden), Sensoren (Modus, aktive App, Wiedergabe,
 * Live-Farben, Verzögerung, Zigbee-Last) und alle Einstellungen aus [HaEntities] als steuerbare Entitäten
 * (Ersatz-, Standard- und Pausenlicht als Licht mit Farbrad).
 */
class HomeAssistant(private val appTopic: String, private val prefix: String, private val clientId: String, private val version: String) {
    val statusTopic = "$appTopic/status"
    val stateTopic = "$appTopic/state"
    val setTopic = "$appTopic/set"
    val modeTopic = "$appTopic/mode"
    val activeAppTopic = "$appTopic/app"
    val playbackTopic = "$appTopic/playback"
    val statsTopic = "$appTopic/stats"
    val configSetFilter = "$appTopic/config/+/set"
    /** Befehle an die App, z. B. `STOP`. */
    val commandTopic = "$appTopic/command"
    /** Aktuelle Lampenfarben (JSON, gedrosselt). */
    val liveTopic = "$appTopic/live"
    /** TV-Status aus Home Assistant (wenn der App eigene Freigaben fehlen). */
    val externalAppTopic = "$appTopic/external/app"
    val externalPlaybackTopic = "$appTopic/external/playback"
    val birthTopic = "$prefix/status"

    private val nodeId = appTopic.replace(Regex("[^A-Za-z0-9_-]"), "_")

    val switchConfigTopic = "$prefix/switch/$nodeId/ambilight/config"
    val sensorConfigTopic = "$prefix/sensor/$nodeId/mode/config"
    private val appSensorConfigTopic = "$prefix/sensor/$nodeId/active_app/config"
    private val playbackConfigTopic = "$prefix/sensor/$nodeId/playback/config"
    private val latencyConfigTopic = "$prefix/sensor/$nodeId/latency/config"
    private val zigbeeConfigTopic = "$prefix/sensor/$nodeId/zigbee_rate/config"
    private val stopButtonConfigTopic = "$prefix/button/$nodeId/stop/config"
    private val liveLeftConfigTopic = "$prefix/sensor/$nodeId/live_left/config"
    private val liveRightConfigTopic = "$prefix/sensor/$nodeId/live_right/config"

    /** Discovery-Topics von Entitäten älterer Versionen (zum Entfernen). */
    fun legacyConfigTopics(): List<String> =
        HaEntities.LEGACY.map { "$prefix/${it.substringBefore('/')}/$nodeId/${it.substringAfter('/')}/config" }

    fun configStateTopic(id: String) = "$appTopic/config/$id"
    fun configSetTopic(id: String) = "$appTopic/config/$id/set"
    fun entityConfigTopic(e: HaEntity) = "$prefix/${e.component}/$nodeId/${e.id}/config"

    /** Entitäts-ID aus einem Befehls-Topic `<app>/config/<id>/set`, sonst null. */
    fun idFromSetTopic(topic: String): String? {
        val start = "$appTopic/config/"
        if (!topic.startsWith(start) || !topic.endsWith("/set")) return null
        return topic.substring(start.length, topic.length - 4).takeIf { it.isNotEmpty() && !it.contains('/') }
    }

    private val device =
        """{"identifiers":[${q(clientId)}],"name":"TV Ambilight","manufacturer":"DIY","model":"Android TV Ambilight","sw_version":${q(version)}}"""

    private val availability = """"availability_topic":${q(statusTopic)}"""

    fun switchConfig(): String = """{"name":"Ambilight","unique_id":${q(clientId + "_ambilight")},""" +
        """"command_topic":${q(setTopic)},"state_topic":${q(stateTopic)},$availability,""" +
        """"payload_on":"ON","payload_off":"OFF","icon":"mdi:television-ambient-light","device":$device}"""

    fun sensorConfig(): String = """{"name":"Ambilight Modus","unique_id":${q(clientId + "_mode")},""" +
        """"state_topic":${q(modeTopic)},$availability,"icon":"mdi:information-outline","device":$device}"""

    private fun appSensorConfig(): String = """{"name":"Aktive App","unique_id":${q(clientId + "_active_app")},""" +
        """"state_topic":${q(activeAppTopic)},$availability,"icon":"mdi:application","device":$device}"""

    private fun playbackSensorConfig(): String = """{"name":"Wiedergabe","unique_id":${q(clientId + "_playback")},""" +
        """"state_topic":${q(playbackTopic)},$availability,"icon":"mdi:play-pause","device":$device}"""

    private fun stopButtonConfig(): String = """{"name":"Ambilight beenden","unique_id":${q(clientId + "_stop")},""" +
        """"command_topic":${q(commandTopic)},"payload_press":"STOP",$availability,"icon":"mdi:stop-circle","device":$device}"""

    private fun liveSensorConfig(side: String, name: String): String =
        """{"name":${q(name)},"unique_id":${q(clientId + "_live_" + side)},"state_topic":${q(liveTopic)},""" +
            """"value_template":${q("{{ value_json.$side }}")},"json_attributes_topic":${q(liveTopic)},""" +
            """"json_attributes_template":${q("{{ {'rgb_color': value_json.${side}_rgb} | tojson }}")},""" +
            """$availability,"icon":"mdi:palette","device":$device}"""

    private fun statsSensorConfig(id: String, name: String, field: String, unit: String, icon: String): String =
        """{"name":${q(name)},"unique_id":${q(clientId + "_" + id)},"state_topic":${q(statsTopic)},""" +
            """"value_template":${q("{{ value_json.$field }}")},"unit_of_measurement":${q(unit)},""" +
            """"state_class":"measurement","entity_category":"diagnostic",$availability,"icon":${q(icon)},"device":$device}"""

    fun entityConfig(e: HaEntity): String {
        val common = """"name":${q(e.name)},"unique_id":${q(clientId + "_" + e.id)},""" +
            """"command_topic":${q(configSetTopic(e.id))},"state_topic":${q(configStateTopic(e.id))},""" +
            """"entity_category":"config","icon":${q(e.icon)},$availability,"device":$device"""
        val specific = when (e) {
            is HaNumber -> """"min":${e.min},"max":${e.max},"step":${e.step},"mode":"slider"""" +
                (e.unit?.let { ""","unit_of_measurement":${q(it)}""" } ?: "")
            is HaSelect -> """"options":[${e.options.joinToString(",") { q(it.label) }}]"""
            is HaSwitch -> """"payload_on":"ON","payload_off":"OFF""""
            is HaLight -> """"schema":"json","supported_color_modes":["rgb"],"brightness_scale":254"""
        }
        return "{$common,$specific}"
    }

    /** Alle Discovery-Configs (Topic → Payload). */
    fun discovery(): List<Pair<String, String>> = buildList {
        add(switchConfigTopic to switchConfig())
        add(sensorConfigTopic to sensorConfig())
        add(appSensorConfigTopic to appSensorConfig())
        add(playbackConfigTopic to playbackSensorConfig())
        add(latencyConfigTopic to statsSensorConfig("latency", "Verzögerung", "latency_ms", "ms", "mdi:timer-outline"))
        add(zigbeeConfigTopic to statsSensorConfig("zigbee_rate", "Zigbee-Befehle", "zigbee_per_s", "1/s", "mdi:zigbee"))
        add(stopButtonConfigTopic to stopButtonConfig())
        add(liveLeftConfigTopic to liveSensorConfig("left", "Farbe links"))
        add(liveRightConfigTopic to liveSensorConfig("right", "Farbe rechts"))
        HaEntities.ALL.forEach { add(entityConfigTopic(it) to entityConfig(it)) }
    }

    companion object {
        /** JSON-String mit Escaping. */
        fun q(s: String): String {
            val sb = StringBuilder(s.length + 2).append('"')
            for (c in s) {
                when {
                    c == '"' -> sb.append("\\\"")
                    c == '\\' -> sb.append("\\\\")
                    c == '\n' -> sb.append("\\n")
                    c == '\r' -> sb.append("\\r")
                    c == '\t' -> sb.append("\\t")
                    c < ' ' -> sb.append(String.format("\\u%04x", c.code))
                    else -> sb.append(c)
                }
            }
            return sb.append('"').toString()
        }
    }
}
