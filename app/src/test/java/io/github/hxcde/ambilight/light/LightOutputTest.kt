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
    fun sceneCutIsConfirmedBySecondFrame() {
        val c = PipelineConfig(smoothing = 0.9f, saturation = 1f)
        val out = LightOutput("l", "z2m")
        out.target(floatArrayOf(0f, 0f, 0f), c)
        // erstes abweichendes Bild: abwarten, alte Farbe halten
        assertEquals(10, out.target(floatArrayOf(1f, 1f, 1f), c).brightness)
        assertFalse(out.lastWasCut)
        // zweites gleiches Bild bestätigt den Schnitt
        assertEquals(254, out.target(floatArrayOf(1f, 1f, 1f), c).brightness)
        assertTrue(out.lastWasCut)
    }

    @Test
    fun singleFlashIsIgnored() {
        val c = PipelineConfig(smoothing = 0.9f, saturation = 1f)
        val out = LightOutput("l", "z2m")
        out.target(floatArrayOf(0f, 0f, 0f), c)
        out.target(floatArrayOf(1f, 1f, 1f), c) // Blitz
        val after = out.target(floatArrayOf(0f, 0f, 0f), c)
        assertFalse(out.lastWasCut)
        assertEquals(10, after.brightness)
    }

    @Test
    fun permanentFlickerFallsBackToSmoothing() {
        val c = PipelineConfig(smoothing = 0.5f, saturation = 1f)
        val out = LightOutput("l", "z2m")
        out.target(floatArrayOf(0f, 0f, 0f), c)
        out.target(floatArrayOf(1f, 0f, 0f), c)
        out.target(floatArrayOf(0f, 0f, 1f), c)
        val b = out.target(floatArrayOf(0f, 1f, 0f), c).brightness
        assertFalse(out.lastWasCut)
        assertTrue("nach max. Wartezeit wird geglättet ($b)", b > 10)
    }

    @Test
    fun smoothingIsIndependentOfFrameRate() {
        val c = PipelineConfig(smoothing = 0.5f, saturation = 1f, sceneCut = false)
        val a = LightOutput("a", "z2m")
        val b = LightOutput("b", "z2m")
        a.target(floatArrayOf(0f, 0f, 0f), c)
        b.target(floatArrayOf(0f, 0f, 0f), c)
        // a: ein Bild nach 200 ms, b: vier Bilder à 50 ms → gleiches Ergebnis
        val ra = a.target(floatArrayOf(1f, 1f, 1f), c, dtMs = 200, intervalMs = 200)
        var rb = b.target(floatArrayOf(1f, 1f, 1f), c, dtMs = 50, intervalMs = 200)
        repeat(3) { rb = b.target(floatArrayOf(1f, 1f, 1f), c, dtMs = 50, intervalMs = 200) }
        assertEquals(ra.brightness, rb.brightness)
    }

    @Test
    fun chromaWeightingUsesBrightPartsForColor() {
        val c = PipelineConfig(smoothing = 0f, saturation = 1f, chromaWeighting = true)
        val out = LightOutput("l", "z2m")
        // Mittelwert dunkles Grau-Blau, Farbton reines Blau
        val t = out.target(floatArrayOf(0.01f, 0.01f, 0.03f), c, chroma = floatArrayOf(0f, 0f, 1f))
        assertEquals(0.1355, t.x, 0.01)
        assertEquals(0.0399, t.y, 0.01)
        // ohne Gewichtung: Farbe aus dem Mittelwert (weniger gesättigt)
        val plain = LightOutput("p", "z2m").target(floatArrayOf(0.01f, 0.01f, 0.03f), c.copy(chromaWeighting = false))
        assertTrue(plain.x > t.x)
    }

    @Test
    fun darkBoostLiftsDarkScenesOnly() {
        val linear = PipelineConfig(smoothing = 0f, saturation = 1f, minBrightness = 60, maxBrightness = 254)
        val boosted = linear.copy(darkBoost = 0.5f)
        val dark = floatArrayOf(0.02f, 0.02f, 0.02f)
        val bLinear = LightOutput("a", "z2m").target(dark, linear).brightness
        val bBoost = LightOutput("b", "z2m").target(dark, boosted).brightness
        assertTrue("dunkel angehoben: $bLinear → $bBoost", bBoost > bLinear + 10)
        // Weiß bleibt bei Maximum, Schwarz bei Minimum
        assertEquals(254, LightOutput("c", "z2m").target(floatArrayOf(1f, 1f, 1f), boosted).brightness)
        assertEquals(60, LightOutput("d", "z2m").target(floatArrayOf(0f, 0f, 0f), boosted).brightness)
    }

    @Test
    fun zigbeeCommandCount() {
        assertEquals(2, LightOutput.zigbeeCommands("""{"state":"ON","brightness":10,"color":{"x":0.3,"y":0.3},"transition":0.20}"""))
        assertEquals(1, LightOutput.zigbeeCommands("""{"color":{"x":0.3,"y":0.3},"transition":0.20}"""))
        assertEquals(1, LightOutput.zigbeeCommands("""{"state":"OFF","transition":1.00}"""))
    }

    @Test
    fun smoothingMovesGradually() {
        val c = PipelineConfig(smoothing = 0.5f, saturation = 1f, sceneCut = false)
        val out = LightOutput("l", "z2m")
        out.target(floatArrayOf(0f, 0f, 0f), c)
        val b = out.target(floatArrayOf(1f, 1f, 1f), c).brightness
        assertTrue("Helligkeit $b sollte zwischen min und max liegen", b in 100..240)
    }
}
