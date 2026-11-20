package io.github.hxcde.ambilight.light

import io.github.hxcde.ambilight.capture.ColorMath
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt

/** Einstellungen für die Umrechnung Bildfarbe → Lampenzustand. */
data class PipelineConfig(
    /** 0 = keine Glättung, 0.9 = sehr träge (Anteil, der pro Sendeintervall vom alten Wert bleibt). */
    val smoothing: Float = 0.3f,
    /** 1.0 = unverändert, 1.5 = 50 % mehr Sättigung. */
    val saturation: Float = 1.3f,
    val minBrightness: Int = 10,
    val maxBrightness: Int = 254,
    /** true = Helligkeit bleibt auf [maxBrightness], es wird nur noch die Farbe gesendet (halbe Zigbee-Last). */
    val fixedBrightness: Boolean = false,
    /** Dunkle Szenen anheben: 0 = lineare Helligkeit, 1 = stark angehoben (Gamma 0,4). */
    val darkBoost: Float = 0f,
    /** Farbton aus den hellen Bildteilen statt aus dem Mittelwert aller Pixel. */
    val chromaWeighting: Boolean = false,
    /** Harter Bildschnitt wird sofort (mit kurzer Überblendung) übernommen statt geglättet. */
    val sceneCut: Boolean = true,
    /** Ab dieser Änderung (max. Kanal-Differenz in sRGB, 0..1) gilt ein Bild als Szenenwechsel. */
    val sceneCutThreshold: Float = 0.35f,
    /** Ab dieser xy-Änderung (|dx|+|dy|) wird neu gesendet. */
    val colorThreshold: Double = 0.005,
    /** Ab dieser Helligkeitsänderung (0..254) wird neu gesendet. */
    val brightnessThreshold: Int = 6,
)

/** Gewünschter Zustand einer Lampe. */
data class LightState(val on: Boolean, val x: Double = ColorMath.WHITE_X, val y: Double = ColorMath.WHITE_Y, val brightness: Int = 0) {
    companion object {
        val OFF = LightState(on = false)

        fun fixed(color: FixedColor, brightness: Int) =
            LightState(on = true, x = color.x, y = color.y, brightness = brightness.coerceIn(1, 254))
    }
}

/**
 * Eine Lampe in Zigbee2MQTT. Hält die geglättete Farbe und den zuletzt gesendeten Zustand,
 * damit nur bei echten Änderungen gesendet wird (schont das Zigbee-Netz).
 */
class LightOutput(val friendlyName: String, baseTopic: String) {
    private val base = baseTopic.trim('/')
    val setTopic: String = "$base/$friendlyName/set"
    val getTopic: String = "$base/$friendlyName/get"
    val stateTopic: String = "$base/$friendlyName"

    private var smoothed: FloatArray? = null
    private var smoothedChroma: FloatArray? = null
    private var lastSent: LightState? = null
    /** Erstes Bild eines möglichen Szenenwechsels; erst das nächste ähnliche Bild bestätigt ihn. */
    private var cutCandidate: FloatArray? = null
    private var cutWait = 0

    /** sRGB-Vorschaufarbe der letzten Berechnung (0..1). */
    var previewSrgb: FloatArray = FloatArray(3)
        private set

    /** true, wenn die letzte Berechnung einen Szenenwechsel erkannt hat. */
    var lastWasCut: Boolean = false
        private set

    /**
     * Lineare Zonenfarbe → Ziel-Zustand (mit Glättung, Szenenwechsel-Erkennung und Sättigung).
     * @param dtMs Zeit seit dem letzten Bild
     * @param intervalMs Sendeintervall; die Glättung ist darauf bezogen und damit unabhängig von der Bildrate
     */
    fun target(
        linear: FloatArray,
        cfg: PipelineConfig,
        dtMs: Long = 200,
        intervalMs: Long = 200,
        chroma: FloatArray = linear,
    ): LightState {
        val prev = smoothed
        val prevChroma = smoothedChroma ?: prev
        lastWasCut = false
        var cut = false
        var hold = false
        val s = if (prev == null) {
            linear.copyOf()
        } else if (cfg.sceneCut && maxSrgbDiff(prev, linear) > cfg.sceneCutThreshold) {
            val candidate = cutCandidate
            when {
                // Zweites Bild bestätigt den Schnitt → übernehmen
                candidate != null && maxSrgbDiff(candidate, linear) <= cfg.sceneCutThreshold / 2 -> {
                    cutCandidate = null
                    cutWait = 0
                    lastWasCut = true
                    cut = true
                    linear.copyOf()
                }
                // Erstes abweichendes Bild: ein Bild abwarten (Blitze, Explosionen, Mündungsfeuer ignorieren)
                cutWait < MAX_CUT_WAIT -> {
                    cutCandidate = linear.copyOf()
                    cutWait++
                    hold = true
                    prev
                }
                // Bild ändert sich dauernd stark: normal glätten
                else -> {
                    cutCandidate = null
                    cutWait = 0
                    smooth(prev, linear, cfg, dtMs, intervalMs)
                }
            }
        } else {
            cutCandidate = null
            cutWait = 0
            smooth(prev, linear, cfg, dtMs, intervalMs)
        }
        smoothed = s
        // Farbton läuft parallel mit (gleiche Glättung, Szenenwechsel und Wartezeit)
        val c = when {
            prev == null || prevChroma == null || cut -> chroma.copyOf()
            hold -> prevChroma
            else -> smooth(prevChroma, chroma, cfg, dtMs, intervalMs)
        }
        smoothedChroma = c

        // Helligkeit aus dem Mittelwert, optional mit angehobenen dunklen Szenen
        val v = run {
            val srgbAvg = FloatArray(3) { ColorMath.linearToSrgb(s[it]) }
            max(srgbAvg[0], max(srgbAvg[1], srgbAvg[2]))
        }
        val gamma = 1.0 - 0.6 * cfg.darkBoost.coerceIn(0f, 1f)
        val level = v.toDouble().pow(gamma).toFloat()

        // Farbe aus dem gewichteten Mittel (helle Bildteile) bzw. dem einfachen Mittelwert
        val colorSource = if (cfg.chromaWeighting) c else s
        val srgb = FloatArray(3) { ColorMath.linearToSrgb(colorSource[it]) }
        val boosted = ColorMath.boostSaturation(srgb, cfg.saturation)
        val bMax = max(boosted[0], max(boosted[1], boosted[2]))
        previewSrgb = if (bMax > 1e-6f) FloatArray(3) { boosted[it] / bMax * level } else boosted
        val minB = cfg.minBrightness.coerceIn(1, 254)
        val maxB = cfg.maxBrightness.coerceIn(minB, 254)
        val brightness = if (cfg.fixedBrightness) {
            maxB
        } else {
            (minB + (maxB - minB) * level).roundToInt().coerceIn(minB, maxB)
        }
        val xy = ColorMath.linearRgbToXy(
            ColorMath.srgbToLinear(boosted[0]),
            ColorMath.srgbToLinear(boosted[1]),
            ColorMath.srgbToLinear(boosted[2]),
        )
        return LightState(true, xy[0], xy[1], brightness)
    }

    private fun smooth(prev: FloatArray, linear: FloatArray, cfg: PipelineConfig, dtMs: Long, intervalMs: Long): FloatArray {
        val keep = cfg.smoothing.coerceIn(0f, 0.95f).toDouble().pow(dtMs.toDouble() / intervalMs.coerceAtLeast(1)).toFloat()
        val alpha = 1f - keep
        return FloatArray(3) { i -> prev[i] + (linear[i] - prev[i]) * alpha }
    }

    private fun maxSrgbDiff(a: FloatArray, b: FloatArray): Float {
        var d = 0f
        for (i in 0..2) d = max(d, abs(ColorMath.linearToSrgb(a[i]) - ColorMath.linearToSrgb(b[i])))
        return d
    }

    /** Setzt die Glättung zurück (z. B. nach Pause), damit nicht von alten Farben übergeblendet wird. */
    fun resetSmoothing() {
        smoothed = null
        smoothedChroma = null
        lastWasCut = false
        cutCandidate = null
        cutWait = 0
    }

    /** Nach Neuverbindung oder Fremdsteuerung: beim nächsten Mal alles senden. */
    fun invalidate() {
        lastSent = null
    }

    /**
     * Baut den Zigbee2MQTT-Payload für [target] oder null, wenn sich nichts Relevantes geändert hat.
     * Pro Feld wird nur gesendet, was sich geändert hat (Farbe und Helligkeit sind getrennte Zigbee-Befehle).
     */
    fun payloadFor(target: LightState, cfg: PipelineConfig, transitionSec: Double, force: Boolean = false): String? {
        val last = lastSent
        val t = String.format(Locale.ROOT, "%.2f", transitionSec.coerceIn(0.0, 10.0))
        if (!target.on) {
            if (!force && last != null && !last.on) return null
            lastSent = LightState.OFF
            return """{"state":"OFF","transition":$t}"""
        }
        val full = force || last == null || !last.on
        val sendBrightness = full || abs(target.brightness - last!!.brightness) >= cfg.brightnessThreshold
        val sendColor = full || abs(target.x - last!!.x) + abs(target.y - last.y) > cfg.colorThreshold
        if (!sendBrightness && !sendColor) return null

        val parts = ArrayList<String>(4)
        if (full) parts += "\"state\":\"ON\""
        if (sendBrightness) parts += "\"brightness\":${target.brightness}"
        if (sendColor) parts += String.format(Locale.ROOT, "\"color\":{\"x\":%.4f,\"y\":%.4f}", target.x, target.y)
        parts += "\"transition\":$t"

        lastSent = LightState(
            on = true,
            x = if (sendColor) target.x else last!!.x,
            y = if (sendColor) target.y else last!!.y,
            brightness = if (sendBrightness) target.brightness else last!!.brightness,
        )
        return parts.joinToString(",", "{", "}")
    }

    companion object {
        /** So viele Bilder wird höchstens auf die Bestätigung eines Szenenwechsels gewartet. */
        private const val MAX_CUT_WAIT = 2

        /** Anzahl Zigbee-Befehle, die Zigbee2MQTT aus einem Payload erzeugt (Helligkeit/Schalten und Farbe sind getrennt). */
        fun zigbeeCommands(payload: String): Int {
            var n = 0
            if (payload.contains("\"brightness\"") || payload.contains("\"state\"")) n++
            if (payload.contains("\"color\"") || payload.contains("\"color_temp\"")) n++
            return n
        }
    }
}
