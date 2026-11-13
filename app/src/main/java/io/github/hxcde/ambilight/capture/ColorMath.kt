package io.github.hxcde.ambilight.capture

import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/** Farbumrechnung sRGB ↔ linear ↔ CIE xy (Philips-Hue-Matrix). */
object ColorMath {
    /** sRGB-Byte (0..255) → linearer Wert (0..1). */
    val SRGB_TO_LINEAR = FloatArray(256) { i ->
        val c = i / 255f
        if (c <= 0.04045f) c / 12.92f else ((c + 0.055f) / 1.055f).pow(2.4f)
    }

    const val WHITE_X = 0.3127
    const val WHITE_Y = 0.3290

    fun linearToSrgb(v: Float): Float {
        val c = v.coerceIn(0f, 1f)
        return if (c <= 0.0031308f) c * 12.92f else 1.055f * c.pow(1f / 2.4f) - 0.055f
    }

    fun srgbToLinear(v: Float): Float {
        val c = v.coerceIn(0f, 1f)
        return if (c <= 0.04045f) c / 12.92f else ((c + 0.055f) / 1.055f).pow(2.4f)
    }

    /** Lineares RGB → CIE-xy mit der von Philips für Hue empfohlenen Wide-Gamut-Matrix. */
    fun linearRgbToXy(r: Float, g: Float, b: Float): DoubleArray {
        val x = r * 0.664511 + g * 0.154324 + b * 0.162028
        val y = r * 0.283881 + g * 0.668433 + b * 0.047685
        val z = r * 0.000088 + g * 0.072310 + b * 0.986039
        val sum = x + y + z
        if (sum <= 1e-9) return doubleArrayOf(WHITE_X, WHITE_Y)
        return doubleArrayOf(x / sum, y / sum)
    }

    /**
     * Erhöht die Sättigung im sRGB-Raum (HSV-Sättigung × [factor]), Farbton und Helligkeit (V) bleiben.
     * Ein- und Ausgabe sind sRGB-Werte 0..1.
     */
    fun boostSaturation(rgb: FloatArray, factor: Float): FloatArray {
        val v = max(rgb[0], max(rgb[1], rgb[2]))
        val mn = min(rgb[0], min(rgb[1], rgb[2]))
        if (v <= 1e-6f || factor == 1f) return rgb.copyOf()
        val s = (v - mn) / v
        if (s <= 1e-6f) return rgb.copyOf()
        val ratio = min(1f, s * factor) / s
        return FloatArray(3) { i -> (v - (v - rgb[i]) * ratio).coerceIn(0f, 1f) }
    }

    /** sRGB 0..1 → Android-Farbe 0xFFRRGGBB (für die Vorschau in der App). */
    fun toArgb(srgb: FloatArray): Int {
        val r = (srgb[0].coerceIn(0f, 1f) * 255f + 0.5f).toInt()
        val g = (srgb[1].coerceIn(0f, 1f) * 255f + 0.5f).toInt()
        val b = (srgb[2].coerceIn(0f, 1f) * 255f + 0.5f).toInt()
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }
}
