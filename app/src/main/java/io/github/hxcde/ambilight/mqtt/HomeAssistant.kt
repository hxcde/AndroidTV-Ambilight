package io.github.hxcde.ambilight.mqtt

/**
 * Topics und MQTT-Discovery-Payloads für Home Assistant:
 * ein Schalter (Ambilight an/pausiert) und ein Sensor (aktueller Modus).
 */
class HomeAssistant(appTopic: String, private val prefix: String, private val clientId: String, private val version: String) {
    val statusTopic = "$appTopic/status"
    val stateTopic = "$appTopic/state"
    val setTopic = "$appTopic/set"
    val modeTopic = "$appTopic/mode"
    val birthTopic = "$prefix/status"

    private val nodeId = appTopic.replace(Regex("[^A-Za-z0-9_-]"), "_")

    val switchConfigTopic = "$prefix/switch/$nodeId/ambilight/config"
    val sensorConfigTopic = "$prefix/sensor/$nodeId/mode/config"

    private val device =
        """{"identifiers":[${q(clientId)}],"name":"TV Ambilight","manufacturer":"DIY","model":"Android TV Ambilight","sw_version":${q(version)}}"""

    fun switchConfig(): String = """{"name":"Ambilight","unique_id":${q(clientId + "_ambilight")},""" +
        """"command_topic":${q(setTopic)},"state_topic":${q(stateTopic)},""" +
        """"availability_topic":${q(statusTopic)},"payload_on":"ON","payload_off":"OFF",""" +
        """"icon":"mdi:television-ambient-light","device":$device}"""

    fun sensorConfig(): String = """{"name":"Ambilight Modus","unique_id":${q(clientId + "_mode")},""" +
        """"state_topic":${q(modeTopic)},"availability_topic":${q(statusTopic)},""" +
        """"icon":"mdi:information-outline","device":$device}"""

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
