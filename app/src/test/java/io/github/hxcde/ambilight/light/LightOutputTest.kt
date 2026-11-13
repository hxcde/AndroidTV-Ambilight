package io.github.hxcde.ambilight.light

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LightOutputTest {
    private val cfg = PipelineConfig(smoothing = 0f, saturation = 1f)

    @Test
    fun topic() {
        assertEquals("zigbee2mqtt/Playbar links/set", LightOutput("Playbar links", "zigbee2mqtt/").setTopic)
    }

    @Test
    fun firstPayloadIsComplete() {
        val out = LightOutput("l", "z2m")
        val p = out.payloadFor(out.target(floatArrayOf(1f, 0f, 0f), cfg), cfg, 0.2)!!
        assertTrue(p.contains("\"state\":\"ON\""))
        assertTrue(p.contains("\"brightness\":254"))
        assertTrue(p.contains("\"color\":{\"x\":0.7006,\"y\":0.2993}"))
        assertTrue(p.contains("\"transition\":0.20"))
    }

    @Test
    fun unchangedSendsNothing() {
        val out = LightOutput("l", "z2m")
        val t = out.target(floatArrayOf(0.2f, 0.4f, 0.1f), cfg)
        assertNotNull(out.payloadFor(t, cfg, 0.2))
        assertNull(out.payloadFor(t, cfg, 0.2))
    }

    @Test
    fun onlyChangedFieldsAreSent() {
        val out = LightOutput("l", "z2m")
        val t = LightState(true, 0.3, 0.3, 100)
        out.payloadFor(t, cfg, 0.2)
        val p = out.payloadFor(t.copy(brightness = 150), cfg, 0.2)!!
        assertTrue(p.contains("\"brightness\":150"))
        assertFalse(p.contains("color"))
        assertFalse(p.contains("state"))
    }

    @Test
    fun offThenOn() {
        val out = LightOutput("l", "z2m")
        out.payloadFor(LightState(true, 0.3, 0.3, 100), cfg, 0.2)
        assertEquals("{\"state\":\"OFF\",\"transition\":1.00}", out.payloadFor(LightState.OFF, cfg, 1.0))
        assertNull(out.payloadFor(LightState.OFF, cfg, 1.0))
        assertTrue(out.payloadFor(LightState(true, 0.3, 0.3, 100), cfg, 0.2)!!.contains("\"state\":\"ON\""))
    }

    @Test
    fun brightnessRange() {
        val c = PipelineConfig(smoothing = 0f, saturation = 1f, minBrightness = 10, maxBrightness = 200)
        val out = LightOutput("l", "z2m")
        assertEquals(10, out.target(floatArrayOf(0f, 0f, 0f), c).brightness)
        out.resetSmoothing()
        assertEquals(200, out.target(floatArrayOf(1f, 1f, 1f), c).brightness)
    }

    @Test
    fun smoothingMovesGradually() {
        val c = PipelineConfig(smoothing = 0.5f, saturation = 1f)
        val out = LightOutput("l", "z2m")
        out.target(floatArrayOf(0f, 0f, 0f), c)
        val b = out.target(floatArrayOf(1f, 1f, 1f), c).brightness
        assertTrue("Helligkeit $b sollte zwischen min und max liegen", b in 100..240)
    }
}
