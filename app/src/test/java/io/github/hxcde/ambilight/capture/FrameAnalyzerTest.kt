package io.github.hxcde.ambilight.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FrameAnalyzerTest {
    private val w = 160
    private val h = 90
    private val stride = w * 4 + 64 // mit Zeilen-Padding wie bei ImageReader

    private fun frame(color: (x: Int, y: Int) -> Int): ByteArray {
        val data = ByteArray(stride * h)
        for (y in 0 until h) for (x in 0 until w) {
            val c = color(x, y)
            val p = y * stride + x * 4
            data[p] = (c shr 16).toByte()
            data[p + 1] = (c shr 8).toByte()
            data[p + 2] = c.toByte()
            data[p + 3] = 0xFF.toByte()
        }
        return data
    }

    private fun analyze(data: ByteArray) = FrameAnalyzer().analyze(data, w, h, stride, 4, 0.3f)

    @Test
    fun leftRedRightBlue() {
        val r = analyze(frame { x, _ -> if (x < w / 2) 0xFF0000 else 0x0000FF })
        assertEquals(1f, r.left[0], 1e-4f)
        assertEquals(0f, r.left[2], 1e-4f)
        assertEquals(0f, r.right[0], 1e-4f)
        assertEquals(1f, r.right[2], 1e-4f)
        assertFalse(r.isBlack)
    }

    @Test
    fun blackFrameDetected() {
        assertTrue(analyze(frame { _, _ -> 0 }).isBlack)
        assertFalse(analyze(frame { _, _ -> 0x101010 }).isBlack)
    }

    @Test
    fun letterboxIsSkipped() {
        // 21:9-Film: oben/unten je 12 Zeilen schwarz, Inhalt grün
        val r = analyze(frame { _, y -> if (y < 12 || y >= h - 12) 0 else 0x00FF00 })
        assertEquals(12, r.contentTop)
        assertEquals(h - 12, r.contentBottom)
        assertEquals(1f, r.left[1], 1e-4f)
    }

    @Test
    fun pillarboxIsSkipped() {
        // 4:3-Inhalt: links/rechts je 20 Spalten schwarz, Inhalt links gelb, rechts cyan
        val r = analyze(frame { x, _ ->
            when {
                x < 20 || x >= w - 20 -> 0
                x < w / 2 -> 0xFFFF00
                else -> 0x00FFFF
            }
        })
        assertEquals(20, r.contentLeft)
        assertEquals(w - 20, r.contentRight)
        assertEquals(1f, r.left[0], 1e-4f)
        assertEquals(1f, r.right[2], 1e-4f)
    }
}
