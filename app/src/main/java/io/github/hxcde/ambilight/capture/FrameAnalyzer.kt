package io.github.hxcde.ambilight.capture

/** Ergebnis eines analysierten Bildes. Farben sind linear (0..1). */
class FrameResult(
    val left: FloatArray,
    val right: FloatArray,
    /** Gesamtes Bild (nahezu) exakt schwarz, typisch für DRM-geschützte Inhalte. */
    val isBlack: Boolean,
    val contentLeft: Int,
    val contentTop: Int,
    val contentRight: Int,
    val contentBottom: Int,
)

/**
 * Berechnet die mittlere Farbe der linken und rechten Randzone eines kleinen RGBA-Bildes.
 * Schwarze Balken (Letterbox oben/unten, Pillarbox links/rechts) werden erkannt und übersprungen.
 */
class FrameAnalyzer {
    /** Pixel mit max(R,G,B) darunter gelten als Balken-Schwarz. */
    var barThreshold = 24
    /** Bild gilt als komplett schwarz, wenn kein Pixel heller ist. */
    var blackThreshold = 4

    private var rowMax = IntArray(0)
    private var colMax = IntArray(0)
    private var lastBounds: IntArray? = null

    /**
     * @param data RGBA-Bytes, Zeilen mit [rowStride] Bytes Abstand
     * @param zoneFraction Anteil der Inhaltsbreite pro Seite (z. B. 0.3)
     */
    fun analyze(
        data: ByteArray,
        width: Int,
        height: Int,
        rowStride: Int,
        pixelStride: Int,
        zoneFraction: Float,
    ): FrameResult {
        if (rowMax.size != height) rowMax = IntArray(height)
        if (colMax.size != width) colMax = IntArray(width)
        rowMax.fill(0)
        colMax.fill(0)
        var frameMax = 0
        for (y in 0 until height) {
            var p = y * rowStride
            var rm = 0
            for (x in 0 until width) {
                val r = data[p].toInt() and 0xFF
                val g = data[p + 1].toInt() and 0xFF
                val b = data[p + 2].toInt() and 0xFF
                val m = if (r > g) (if (r > b) r else b) else (if (g > b) g else b)
                if (m > rm) rm = m
                if (m > colMax[x]) colMax[x] = m
                p += pixelStride
            }
            rowMax[y] = rm
            if (rm > frameMax) frameMax = rm
        }

        val isBlack = frameMax <= blackThreshold
        val bounds = if (frameMax <= barThreshold) {
            // Sehr dunkle Szene: Balken nicht sicher erkennbar, letzte Werte behalten
            lastBounds ?: intArrayOf(0, 0, width, height)
        } else {
            detectBounds(width, height).also { lastBounds = it }
        }
        val cl = bounds[0]
        val ct = bounds[1]
        val cr = bounds[2]
        val cb = bounds[3]

        val contentWidth = cr - cl
        val zone = (contentWidth * zoneFraction.coerceIn(0.05f, 0.5f)).toInt().coerceAtLeast(1)
        val left = averageLinear(data, rowStride, pixelStride, cl, cl + zone, ct, cb)
        val right = averageLinear(data, rowStride, pixelStride, cr - zone, cr, ct, cb)
        return FrameResult(left, right, isBlack, cl, ct, cr, cb)
    }

    private fun detectBounds(width: Int, height: Int): IntArray {
        val maxBarY = height / 4
        val maxBarX = width / 4
        var top = 0
        while (top < maxBarY && rowMax[top] <= barThreshold) top++
        var bottom = height
        while (height - bottom < maxBarY && rowMax[bottom - 1] <= barThreshold) bottom--
        var left = 0
        while (left < maxBarX && colMax[left] <= barThreshold) left++
        var right = width
        while (width - right < maxBarX && colMax[right - 1] <= barThreshold) right--
        return intArrayOf(left, top, right, bottom)
    }

    private fun averageLinear(
        data: ByteArray,
        rowStride: Int,
        pixelStride: Int,
        x0: Int,
        x1: Int,
        y0: Int,
        y1: Int,
    ): FloatArray {
        val lut = ColorMath.SRGB_TO_LINEAR
        var r = 0.0
        var g = 0.0
        var b = 0.0
        var n = 0
        for (y in y0 until y1) {
            var p = y * rowStride + x0 * pixelStride
            for (x in x0 until x1) {
                r += lut[data[p].toInt() and 0xFF]
                g += lut[data[p + 1].toInt() and 0xFF]
                b += lut[data[p + 2].toInt() and 0xFF]
                n++
                p += pixelStride
            }
        }
        if (n == 0) return FloatArray(3)
        return floatArrayOf((r / n).toFloat(), (g / n).toFloat(), (b / n).toFloat())
    }
}
