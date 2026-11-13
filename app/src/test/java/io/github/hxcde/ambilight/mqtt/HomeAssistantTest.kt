package io.github.hxcde.ambilight.mqtt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeAssistantTest {
    private val ha = HomeAssistant("tv-ambilight", "homeassistant", "tv-ambilight-abc123", "0.1.0")

    @Test
    fun topics() {
        assertEquals("tv-ambilight/set", ha.setTopic)
        assertEquals("homeassistant/switch/tv-ambilight/ambilight/config", ha.switchConfigTopic)
        assertEquals("homeassistant/sensor/tv-ambilight/mode/config", ha.sensorConfigTopic)
    }

    @Test
    fun nodeIdIsSanitized() {
        val h = HomeAssistant("wohnzimmer/tv ambi", "homeassistant", "id", "1")
        assertEquals("homeassistant/switch/wohnzimmer_tv_ambi/ambilight/config", h.switchConfigTopic)
    }

    @Test
    fun switchConfigContainsTopics() {
        val c = ha.switchConfig()
        assertTrue(c.contains("\"command_topic\":\"tv-ambilight/set\""))
        assertTrue(c.contains("\"availability_topic\":\"tv-ambilight/status\""))
        assertTrue(c.contains("\"unique_id\":\"tv-ambilight-abc123_ambilight\""))
    }

    @Test
    fun jsonEscaping() {
        assertEquals("\"a\\\"b\\\\c\\n\"", HomeAssistant.q("a\"b\\c\n"))
    }
}
