package io.github.hxcde.ambilight.light

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FixedColorTest {
    @Test
    fun presets() {
        assertEquals(FixedColor.DEFAULT, FixedColor.parse("warm"))
        assertEquals(0.3127, FixedColor.parse("cold")!!.x, 1e-6)
    }

    @Test
    fun hexColors() {
        val blue = FixedColor.parse("#0000FF")!!
        assertEquals(0.1355, blue.x, 1e-3)
        assertEquals(0.0399, blue.y, 1e-3)
        assertEquals(0xFF0000FF.toInt(), blue.previewArgb)
        val purple = FixedColor.fromHex("8000ff")!!
        assertTrue(purple.x < 0.3 && purple.y < 0.15)
    }

    @Test
    fun invalid() {
        assertNull(FixedColor.parse("#12345"))
        assertNull(FixedColor.parse("#GG0000"))
        assertNull(FixedColor.parse("#000000"))
        assertNull(FixedColor.parse(null))
    }
}
