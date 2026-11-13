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

class HaEntitiesTest {
    private val ha = HomeAssistant("tv-ambilight", "homeassistant", "id", "1")

    @Test
    fun setTopicParsing() {
        assertEquals("brightness_max", ha.idFromSetTopic("tv-ambilight/config/brightness_max/set"))
        assertEquals(null, ha.idFromSetTopic("tv-ambilight/config/brightness_max"))
        assertEquals(null, ha.idFromSetTopic("tv-ambilight/set"))
    }

    @Test
    fun numberClampsAndRounds() {
        val n = HaEntities.byId("brightness_max") as HaNumber
        assertEquals(254, n.parse("300"))
        assertEquals(128, n.parse("127.6"))
        assertEquals(null, n.parse("abc"))
    }

    @Test
    fun selectMapsLabelsToValues() {
        val s = HaEntities.byId("black_mode") as HaSelect
        assertEquals("OFF", s.parse("Licht aus"))
        assertEquals("Letzte Farbe halten", s.label("HOLD"))
        assertEquals(null, s.parse("Gibt es nicht"))
    }

    @Test
    fun discoveryContainsAllEntities() {
        val d = ha.discovery()
        assertEquals(9 + HaEntities.ALL.size, d.size)
        val select = d.first { it.first == "homeassistant/select/tv-ambilight/black_mode/config" }.second
        assertTrue(select, select.contains("\"options\":[\"Feste Farbe (pro Lampe)\""))
        val number = d.first { it.first == "homeassistant/number/tv-ambilight/smoothing/config" }.second
        assertTrue(number, number.contains("\"unit_of_measurement\":\"%\""))
        val light = d.first { it.first == "homeassistant/light/tv-ambilight/pause_light/config" }.second
        assertTrue(light, light.contains("\"schema\":\"json\"") && light.contains("\"supported_color_modes\":[\"rgb\"]"))
        val button = d.first { it.first == "homeassistant/button/tv-ambilight/stop/config" }.second
        assertTrue(button, button.contains("\"payload_press\":\"STOP\""))
    }

    @Test
    fun lightCommandAndState() {
        assertEquals(HaLight.Command("#8000FF", 120), HaLight.parse("""{"state":"ON","color":{"r":128,"g":0,"b":255},"brightness":120}"""))
        assertEquals(HaLight.Command(null, 254), HaLight.parse("""{"state":"ON","brightness":300}"""))
        assertEquals(HaLight.Command(null, null), HaLight.parse("""{"state":"ON","color":{"r":0,"g":0,"b":0}}"""))
        assertEquals(null, HaLight.parse("kaputt"))
        val l = HaEntities.byId("standard_light_left") as HaLight
        assertEquals(
            """{"state":"ON","color_mode":"rgb","brightness":150,"color":{"r":128,"g":0,"b":255}}""",
            l.state(io.github.hxcde.ambilight.light.FixedColor.parse("#8000FF")!!, 150),
        )
    }

    @Test
    fun brightnessKeyAffectsBothLights() {
        val ids = HaEntities.affectedBy("standard_brightness").map { it.id }
        assertEquals(listOf("standard_light_left", "standard_light_right"), ids)
        assertEquals(listOf("black_light_left"), HaEntities.affectedBy("black_color_left_custom").map { it.id })
    }

    @Test
    fun legacyTopicsAreRemoved() {
        assertTrue(ha.legacyConfigTopics().contains("homeassistant/select/tv-ambilight/black_color_left/config"))
    }
}
