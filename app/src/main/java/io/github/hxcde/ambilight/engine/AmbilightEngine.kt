package io.github.hxcde.ambilight.engine

import io.github.hxcde.ambilight.BlackMode
import io.github.hxcde.ambilight.capture.ColorMath
import io.github.hxcde.ambilight.capture.FrameResult
import io.github.hxcde.ambilight.light.FixedColor
import io.github.hxcde.ambilight.light.LightOutput
import io.github.hxcde.ambilight.light.LightState
import io.github.hxcde.ambilight.light.PipelineConfig

/** Alle Einstellungen, die die Engine zur Laufzeit braucht. Kann jederzeit ersetzt werden. */
data class EngineConfig(
    val pipeline: PipelineConfig = PipelineConfig(),
    /** Normales Sendeintervall pro Lampe (1000 / Updates pro Sekunde). */
    val sendIntervalMs: Long = 200,
    val transitionSec: Double = 0.2,
    val blackMode: BlackMode = BlackMode.COLOR,
    val blackBrightness: Int = 80,
    val blackColorLeft: FixedColor = FixedColor.DEFAULT,
    val blackColorRight: FixedColor = FixedColor.DEFAULT,
    val blackDelayMs: Long = 2_000,
    val standbyAction: LightAction = LightAction.RESTORE,
)

/** Ziel der Engine: MQTT-Nachrichten und Statusänderungen. */
interface EngineOutput {
    fun publish(topic: String, payload: String)
    fun modeChanged(mode: Mode) {}
    fun preview(left: Int, right: Int) {}
}

/**
 * Steuerlogik ohne Android-Abhängigkeiten: entscheidet pro Bild, was an welche Lampe geht.
 *
 * - Modi mit Priorität: Standby > Pause (Home Assistant) > App-Profil > geschützter Inhalt > aktiv
 * - Szenenwechsel werden sofort und ohne Überblendung gesendet, sonst höchstens alle [EngineConfig.sendIntervalMs]
 * - Vor dem ersten Senden wird der Lampenzustand gesichert ([LightSnapshot]) und bei Freigabe wiederhergestellt
 * - Statistik: Bilder, MQTT-Nachrichten, Zigbee-Befehle, Verzögerung bis zur Statusmeldung von Zigbee2MQTT
 *
 * Nicht threadsicher: alle Aufrufe vom selben Thread.
 */
class AmbilightEngine(
    left: LightOutput,
    right: LightOutput,
    config: EngineConfig,
    private val out: EngineOutput,
) {
    val outputs: List<LightOutput> = listOf(left, right)

    var config: EngineConfig = config
        private set

    var mode: Mode = Mode.ACTIVE
        private set

    // Eingänge
    private var screenOn = true
    private var haPaused = false
    private var appAction = AppAction.AMBILIGHT

    // Schwarzbild
    private var protectedActive = false
    private var blackSince = 0L

    // Senden
    private val lastSendAt = LongArray(2)
    private val pendingCut = BooleanArray(2)
    private var lastFrameAt = 0L

    // Lampenzustand vor dem Ambilight
    private val snapshots = arrayOfNulls<LightSnapshot>(2)
    private var snapshotUntil = 0L
    private var snapshotTaken = false
    /** Lampen stehen gerade auf einem Zustand, den der Nutzer bzw. Home Assistant bestimmt hat. */
    private var lampsInUserState = false

    // Statistik
    private var frames = 0
    private var messages = 0
    private var zigbee = 0
    private var statsSince = 0L
    private val awaitingEcho = LongArray(2)
    private val latencies = ArrayDeque<Int>()

    fun updateConfig(newConfig: EngineConfig, now: Long) {
        val old = config
        config = newConfig
        // Feste Farben/Helligkeit im Ersatzmodus sofort neu anwenden
        if ((mode == Mode.PROTECTED || mode == Mode.APP_FALLBACK) &&
            (old.blackMode != newConfig.blackMode || old.blackColorLeft != newConfig.blackColorLeft ||
                old.blackColorRight != newConfig.blackColorRight || old.blackBrightness != newConfig.blackBrightness)
        ) {
            applyFallback()
        }
        if (old.blackMode != newConfig.blackMode) checkBlack(now)
    }

    // ------------------------------------------------------------ Eingänge

    fun setScreenOn(on: Boolean, now: Long) {
        if (screenOn == on) return
        screenOn = on
        if (on) blackSince = 0L
        updateMode(now)
    }

    fun setHaPaused(paused: Boolean, now: Long) {
        if (haPaused == paused) return
        haPaused = paused
        if (paused) lampsInUserState = true
        updateMode(now)
    }

    fun setAppAction(action: AppAction, now: Long) {
        if (appAction == action) return
        appAction = action
        updateMode(now)
    }

    /** MQTT (neu) verbunden: alles neu senden, beim ersten Mal Lampenzustand sichern. */
    fun onConnected(now: Long) {
        outputs.forEach { it.invalidate() }
        if (!snapshotTaken) startSnapshot(now)
        else reapplyMode()
    }

    /** Statusmeldung von `zigbee2mqtt/<Lampe>`. */
    fun onLightState(topic: String, json: String, now: Long) {
        val i = outputs.indexOfFirst { it.stateTopic == topic }
        if (i < 0) return
        if (awaitingEcho[i] != 0L) {
            addLatency((now - awaitingEcho[i]).toInt())
            awaitingEcho[i] = 0L
        }
        if (snapshotUntil != 0L && snapshots[i] == null) {
            snapshots[i] = LightSnapshot.parse(json)
            if (snapshots.all { it != null }) snapshotUntil = 0L
        }
    }

    fun onFrame(r: FrameResult, now: Long) {
        frames++
        if (!screenOn) return
        if (r.isBlack) {
            if (blackSince == 0L) blackSince = now
        } else {
            blackSince = 0L
            if (protectedActive) {
                protectedActive = false
                updateMode(now)
            }
        }
        checkBlack(now)
        if (mode != Mode.ACTIVE || isSnapshotPending(now)) {
            lastFrameAt = 0L
            return
        }
        // Bis zum Ersatzmodus letzte echte Farbe stehen lassen (nicht auf Schwarz abdunkeln)
        if (r.isBlack && config.blackMode != BlackMode.NORMAL) return

        val interval = config.sendIntervalMs.coerceAtLeast(1)
        val dt = if (lastFrameAt == 0L) interval else (now - lastFrameAt).coerceIn(1, 1_000)
        lastFrameAt = now
        val zones = arrayOf(r.left, r.right)
        for (i in 0..1) {
            val o = outputs[i]
            val target = o.target(zones[i], config.pipeline, dt, interval)
            if (o.lastWasCut) pendingCut[i] = true
            val since = now - lastSendAt[i]
            val due = since >= interval || (pendingCut[i] && since >= MIN_CUT_GAP_MS)
            if (!due) continue
            val transition = if (pendingCut[i]) 0.0 else config.transitionSec
            val payload = o.payloadFor(target, config.pipeline, transition) ?: continue
            send(i, payload, now)
            lastSendAt[i] = now
            pendingCut[i] = false
        }
        out.preview(ColorMath.toArgb(outputs[0].previewSrgb), ColorMath.toArgb(outputs[1].previewSrgb))
    }

    /** Regelmäßig aufrufen (z. B. alle 500 ms): Zeitüberschreitungen prüfen. */
    fun tick(now: Long) {
        checkBlack(now)
        if (snapshotUntil != 0L && now >= snapshotUntil) snapshotUntil = 0L
    }

    /** Nachrichten für das Beenden (Lampen freigeben). */
    fun finalMessages(action: LightAction): List<Pair<String, String>> {
        if (mode == Mode.PAUSED) return emptyList() // Lampen gehören gerade Home Assistant
        return (0..1).mapNotNull { i -> releasePayload(i, action)?.let { outputs[i].setTopic to it } }
    }

    /** Statistik seit dem letzten Aufruf. */
    fun collectStats(now: Long): EngineStats {
        val secs = if (statsSince == 0L) 1f else ((now - statsSince).coerceAtLeast(1)) / 1000f
        val s = EngineStats(
            framesPerSec = frames / secs,
            messagesPerSec = messages / secs,
            zigbeePerSec = zigbee / secs,
            latencyAvgMs = if (latencies.isEmpty()) null else latencies.average().toInt(),
            latencyMaxMs = latencies.maxOrNull(),
        )
        frames = 0
        messages = 0
        zigbee = 0
        statsSince = now
        return s
    }

    // ------------------------------------------------------------ intern

    private fun desiredMode(): Mode = when {
        !screenOn -> Mode.STANDBY
        haPaused -> Mode.PAUSED
        appAction == AppAction.OFF -> Mode.APP_OFF
        appAction == AppAction.RESTORE -> Mode.APP_RESTORE
        appAction == AppAction.FALLBACK -> Mode.APP_FALLBACK
        protectedActive -> Mode.PROTECTED
        else -> Mode.ACTIVE
    }

    private fun updateMode(now: Long) {
        val new = desiredMode()
        if (new == mode) return
        mode = new
        when (new) {
            Mode.ACTIVE -> {
                resetLights()
                // Hat jemand anderes die Lampen zwischenzeitlich gesteuert, diesen Zustand als neuen Ausgangspunkt sichern
                if (lampsInUserState) startSnapshot(now)
            }
            else -> reapplyMode()
        }
        out.modeChanged(new)
    }

    /** Lichtbefehle für den aktuellen (nicht aktiven) Modus senden. */
    private fun reapplyMode() {
        when (mode) {
            Mode.STANDBY -> release(config.standbyAction)
            Mode.APP_RESTORE -> release(LightAction.RESTORE)
            Mode.APP_OFF -> release(LightAction.OFF)
            Mode.PROTECTED, Mode.APP_FALLBACK -> applyFallback()
            Mode.ACTIVE, Mode.PAUSED, Mode.STOPPED -> Unit
        }
    }

    private fun checkBlack(now: Long) {
        if (protectedActive || blackSince == 0L || config.blackMode == BlackMode.NORMAL) return
        if (now - blackSince < config.blackDelayMs) return
        protectedActive = true
        updateMode(now)
    }

    private fun applyFallback() {
        when (config.blackMode) {
            BlackMode.COLOR -> {
                send(0, outputs[0].payloadFor(LightState.fixed(config.blackColorLeft, config.blackBrightness), config.pipeline, 1.0, force = true), 0)
                send(1, outputs[1].payloadFor(LightState.fixed(config.blackColorRight, config.blackBrightness), config.pipeline, 1.0, force = true), 0)
                out.preview(config.blackColorLeft.previewArgb, config.blackColorRight.previewArgb)
            }
            BlackMode.OFF -> {
                for (i in 0..1) send(i, outputs[i].payloadFor(LightState.OFF, config.pipeline, 1.0, force = true), 0)
                out.preview(BLACK, BLACK)
            }
            BlackMode.HOLD, BlackMode.NORMAL -> Unit
        }
    }

    private fun release(action: LightAction) {
        for (i in 0..1) releasePayload(i, action)?.let { send(i, it, 0) }
        if (action == LightAction.RESTORE) lampsInUserState = true
        out.preview(if (action == LightAction.NONE) PREVIEW_UNCHANGED else BLACK, if (action == LightAction.NONE) PREVIEW_UNCHANGED else BLACK)
    }

    private fun releasePayload(i: Int, action: LightAction): String? {
        val o = outputs[i]
        return when (action) {
            LightAction.NONE -> null
            LightAction.OFF -> o.payloadFor(LightState.OFF, config.pipeline, 1.0, force = true)
            LightAction.RESTORE -> {
                o.invalidate()
                snapshots[i]?.payload(1.0) ?: o.payloadFor(LightState.OFF, config.pipeline, 1.0, force = true)
            }
        }
    }

    private fun startSnapshot(now: Long) {
        snapshots.fill(null)
        snapshotTaken = true
        lampsInUserState = false
        snapshotUntil = now + SNAPSHOT_TIMEOUT_MS
        outputs.forEach { out.publish(it.getTopic, LightSnapshot.GET_PAYLOAD) }
    }

    private fun isSnapshotPending(now: Long): Boolean {
        if (snapshotUntil == 0L) return false
        if (now >= snapshotUntil) {
            snapshotUntil = 0L
            return false
        }
        return true
    }

    private fun resetLights() {
        blackSince = 0L
        lastFrameAt = 0L
        pendingCut.fill(false)
        lastSendAt.fill(0L)
        outputs.forEach {
            it.resetSmoothing()
            it.invalidate()
        }
    }

    private fun send(i: Int, payload: String?, now: Long) {
        if (payload == null) return
        out.publish(outputs[i].setTopic, payload)
        messages++
        zigbee += LightOutput.zigbeeCommands(payload)
        if (now != 0L) awaitingEcho[i] = now
    }

    private fun addLatency(ms: Int) {
        if (ms < 0 || ms > 10_000) return
        latencies.addLast(ms)
        while (latencies.size > LATENCY_SAMPLES) latencies.removeFirst()
    }

    companion object {
        /** Mindestabstand zweier Befehle an dieselbe Lampe bei Szenenwechseln. */
        const val MIN_CUT_GAP_MS = 100L
        const val SNAPSHOT_TIMEOUT_MS = 2_000L
        private const val LATENCY_SAMPLES = 30
        private const val BLACK = 0xFF000000.toInt()
        private const val PREVIEW_UNCHANGED = 0xFF2A3540.toInt()
    }
}
