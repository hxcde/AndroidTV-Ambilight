package io.github.hxcde.ambilight.mqtt

/**
 * Topics und MQTT-Discovery-Payloads für Home Assistant:
 * Schalter (Ambilight an/pausiert), Sensoren (Modus, aktive App, Verzögerung, Zigbee-Last)
 * und alle Einstellungen aus [HaEntities] als steuerbare Entitäten.
 */
class HomeAssistant(private val appTopic: String, private val prefix: String, private val clientId: String, private val version: String) {
    val statusTopic = "$appTopic/status"
    val stateTopic = "$appTopic/state"
    val setTopic = "$appTopic/set"
    val modeTopic = "$appTopic/mode"
    val activeAppTopic = "$appTopic/app"
    val statsTopic = "$appTopic/stats"
    val configSetFilter = "$appTopic/config/+/set"
    val birthTopic = "$prefix/status"

    private val nodeId = appTopic.replace(Regex("[^A-Za-z0-9_-]"), "_")

    val switchConfigTopic = "$prefix/switch/$nodeId/ambilight/config"
    val sensorConfigTopic = "$prefix/sensor/$nodeId/mode/config"
    private val appSensorConfigTopic = "$prefix/sensor/$nodeId/active_app/config"
    private val latencyConfigTopic = "$prefix/sensor/$nodeId/latency/config"
    private val zigbeeConfigTopic = "$prefix/sensor/$nodeId/zigbee_rate/config"

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
        }
        return "{$common,$specific}"
    }

    /** Alle Discovery-Configs (Topic → Payload). */
    fun discovery(): List<Pair<String, String>> = buildList {
        add(switchConfigTopic to switchConfig())
        add(sensorConfigTopic to sensorConfig())
        add(appSensorConfigTopic to appSensorConfig())
        add(latencyConfigTopic to statsSensorConfig("latency", "Verzögerung", "latency_ms", "ms", "mdi:timer-outline"))
        add(zigbeeConfigTopic to statsSensorConfig("zigbee_rate", "Zigbee-Befehle", "zigbee_per_s", "1/s", "mdi:zigbee"))
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
