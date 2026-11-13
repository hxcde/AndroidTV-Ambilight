package io.github.hxcde.ambilight.light

import io.github.hxcde.ambilight.capture.ColorMath

/**
 * Feste Lampenfarbe für den Ersatzmodus (geschützte Inhalte).
 * Weißtöne als exakte xy-Werte, Farben als sRGB-Hex (#RRGGBB).
 */
data class FixedColor(val x: Double, val y: Double, val previewArgb: Int) {
    companion object {
        const val WARM = "warm"
        const val NEUTRAL = "neutral"
        const val COLD = "cold"
        const val CUSTOM = "custom"

        val DEFAULT = FixedColor(0.4599, 0.4106, 0xFFFFC37A.toInt()) // ca. 2700 K

        /** Wandelt einen Listenwert (warm/neutral/cold/#RRGGBB) in eine Farbe um, null bei ungültigem Wert. */
        fun parse(value: String?): FixedColor? {
            val v = value?.trim()?.lowercase() ?: return null
            return when (v) {
                WARM -> DEFAULT
                NEUTRAL -> FixedColor(0.3804, 0.3768, 0xFFFFE3C4.toInt()) // ca. 4000 K
                COLD -> FixedColor(0.3127, 0.3290, 0xFFF2F6FF.toInt()) // ca. 6500 K
                else -> fromHex(v)
            }
        }

        fun fromHex(hex: String): FixedColor? {
            val h = hex.trim().removePrefix("#")
            if (h.length != 6 || !h.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) return null
            val rgb = h.toInt(16)
            val r = (rgb shr 16) and 0xFF
            val g = (rgb shr 8) and 0xFF
            val b = rgb and 0xFF
            if (r == 0 && g == 0 && b == 0) return null // Schwarz ist keine Lampenfarbe
            val lut = ColorMath.SRGB_TO_LINEAR
            val xy = ColorMath.linearRgbToXy(lut[r], lut[g], lut[b])
            return FixedColor(xy[0], xy[1], (0xFF shl 24) or rgb)
        }

        fun isValidHex(hex: String?): Boolean = hex != null && fromHex(hex) != null
    }
}
