package io.github.hxcde.ambilight.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LightSnapshotTest {
    @Test
    fun parseXyState() {
        val s = LightSnapshot.parse(
            """{"brightness":200,"color":{"hue":30,"saturation":80,"x":0.4599,"y":0.4106},"color_mode":"xy","color_temp":370,"linkquality":120,"state":"ON"}""",
        )!!
        assertEquals(true, s.on)
        assertEquals("""{"state":"ON","brightness":200,"color":{"x":0.4599,"y":0.4106},"transition":1.00}""", s.payload(1.0))
    }

    @Test
    fun parseColorTempState() {
        val s = LightSnapshot.parse("""{"brightness":50,"color":{"x":0.3,"y":0.3},"color_mode":"color_temp","color_temp":250,"state":"ON"}""")!!
        assertEquals("""{"state":"ON","brightness":50,"color_temp":250,"transition":0.50}""", s.payload(0.5))
    }

    @Test
    fun parseOff() {
        assertEquals("""{"state":"OFF","transition":1.00}""", LightSnapshot.parse("""{"state":"OFF","brightness":10}""")!!.payload(1.0))
    }

    @Test
    fun invalid() {
        assertNull(LightSnapshot.parse("""{"linkquality":80}"""))
        assertNull(LightSnapshot.parse("kein json"))
    }
}
