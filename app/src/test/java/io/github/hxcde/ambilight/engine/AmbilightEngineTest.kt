package io.github.hxcde.ambilight.engine

import io.github.hxcde.ambilight.BlackMode
import io.github.hxcde.ambilight.capture.ColorMath
import io.github.hxcde.ambilight.capture.FrameResult
import io.github.hxcde.ambilight.light.FixedColor
import io.github.hxcde.ambilight.light.LightOutput
import io.github.hxcde.ambilight.light.PipelineConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class AmbilightEngineTest {
    private val sent = ArrayList<Pair<String, String>>()
    private val modes = ArrayList<Mode>()
    private lateinit var engine: AmbilightEngine

    private val leftSet = "z2m/L/set"
    private val rightSet = "z2m/R/set"

    private val config = EngineConfig(
        pipeline = PipelineConfig(smoothing = 0f, saturation = 1f),
        sendIntervalMs = 200,
        transitionSec = 0.2,
        blackMode = BlackMode.COLOR,
        blackColorLeft = FixedColor.parse("#8000FF")!!,
        blackColorRight = FixedColor.parse("#0000FF")!!,
        standbyAction = LightAction.RESTORE,
        standardLight = LightScene(FixedColor.DEFAULT, FixedColor.parse("#FF6A00")!!, 150),
        pauseLight = LightScene(FixedColor.DEFAULT, FixedColor.DEFAULT, 230),
    )

    @Before
    fun setUp() {
        engine = AmbilightEngine(LightOutput("L", "z2m"), LightOutput("R", "z2m"), config, object : EngineOutput {
            override fun publish(topic: String, payload: String) {
                sent += topic to payload
            }

            override fun modeChanged(mode: Mode) {
                modes += mode
            }
        })
    }

    private fun lin(rgb: Int) = floatArrayOf(
        ColorMath.SRGB_TO_LINEAR[(rgb shr 16) and 0xFF],
        ColorMath.SRGB_TO_LINEAR[(rgb shr 8) and 0xFF],
        ColorMath.SRGB_TO_LINEAR[rgb and 0xFF],
    )

    private fun frame(left: Int, right: Int, black: Boolean = false) =
        FrameResult(lin(left), lin(right), black, 0, 0, 160, 90)

    /** Verbindung + beantworteter Snapshot (Lampen waren an, warmweiß). */
    private fun connectWithSnapshot(now: Long = 1_000) {
        engine.onConnected(now)
        engine.onLightState("z2m/L", """{"state":"ON","brightness":120,"color_mode":"color_temp","color_temp":370}""", now + 10)
        engine.onLightState("z2m/R", """{"state":"OFF"}""", now + 10)
        sent.clear()
    }

    private fun sentTo(topic: String) = sent.filter { it.first == topic }.map { it.second }

    @Test
    fun snapshotIsRequestedAndBlocksSendingUntilAnswered() {
        engine.onConnected(1_000)
        assertEquals(listOf("z2m/L/get", "z2m/R/get"), sent.map { it.first })
        sent.clear()
        engine.onFrame(frame(0xFF0000, 0x0000FF), 1_100)
        assertTrue("vor dem Snapshot darf nichts gesendet werden", sent.isEmpty())
        engine.onLightState("z2m/L", """{"state":"ON","brightness":100}""", 1_150)
        engine.onLightState("z2m/R", """{"state":"OFF"}""", 1_160)
        engine.onFrame(frame(0xFF0000, 0x0000FF), 1_200)
        assertEquals(1, sentTo(leftSet).size)
        assertEquals(1, sentTo(rightSet).size)
    }

    @Test
    fun snapshotTimeoutDoesNotBlockForever() {
        engine.onConnected(1_000)
        sent.clear()
        engine.onFrame(frame(0xFF0000, 0x0000FF), 1_000 + AmbilightEngine.SNAPSHOT_TIMEOUT_MS + 1)
        assertEquals(2, sent.size)
    }

    @Test
    fun sendIntervalIsRespected() {
        connectWithSnapshot()
        engine.onFrame(frame(0x804020, 0x204080), 2_000)
        sent.clear()
        // kleine Änderung nach 66 ms: noch nicht fällig
        engine.onFrame(frame(0x904020, 0x204090), 2_066)
        assertTrue(sent.isEmpty())
        // nach 200 ms wieder fällig
        engine.onFrame(frame(0x904020, 0x204090), 2_200)
        assertEquals(2, sent.size)
    }

    @Test
    fun sceneCutIsSentImmediatelyWithoutTransition() {
        connectWithSnapshot()
        engine.onFrame(frame(0x101010, 0x101010), 2_000)
        sent.clear()
        engine.onFrame(frame(0xFF0000, 0x0000FF), 2_120) // > MIN_CUT_GAP, < Sendeintervall
        val p = sentTo(leftSet).single()
        assertTrue(p, p.contains("\"transition\":0.00"))
    }

    @Test
    fun sceneCutCanBeDisabled() {
        connectWithSnapshot()
        engine.updateConfig(config.copy(pipeline = config.pipeline.copy(sceneCut = false)), 1_500)
        engine.onFrame(frame(0x101010, 0x101010), 2_000)
        sent.clear()
        engine.onFrame(frame(0xFF0000, 0x0000FF), 2_120)
        assertTrue(sent.isEmpty())
    }

    @Test
    fun fixedBrightnessSendsOnlyColorAfterFirstUpdate() {
        connectWithSnapshot()
        engine.updateConfig(config.copy(pipeline = config.pipeline.copy(fixedBrightness = true, sceneCut = false)), 1_500)
        engine.onFrame(frame(0xFF0000, 0x0000FF), 2_000)
        sent.clear()
        engine.onFrame(frame(0x400000, 0x000040), 2_200) // dunkler, gleiche Farbe → nichts
        assertTrue(sent.isEmpty())
        engine.onFrame(frame(0x00FF00, 0x00FF00), 2_400)
        val p = sentTo(leftSet).single()
        assertFalse(p.contains("brightness"))
        assertEquals(1, LightOutput.zigbeeCommands(p))
    }

    @Test
    fun blackFrameSwitchesToFallbackColorsAfterDelay() {
        connectWithSnapshot()
        engine.onFrame(frame(0xFF0000, 0x0000FF), 2_000)
        sent.clear()
        engine.onFrame(frame(0, 0, black = true), 2_500)
        assertTrue("vor Ablauf wird die letzte Farbe gehalten", sent.isEmpty())
        engine.tick(4_600)
        assertEquals(Mode.PROTECTED, engine.mode)
        assertTrue(sentTo(leftSet).single().contains("\"x\":0.2"))
        sent.clear()
        engine.onFrame(frame(0x00FF00, 0x00FF00), 5_000)
        assertEquals(Mode.ACTIVE, engine.mode)
        assertEquals(2, sent.size)
    }

    @Test
    fun appProfileFallbackIsImmediate() {
        connectWithSnapshot()
        engine.setAppAction(AppAction.FALLBACK, 2_000)
        assertEquals(Mode.APP_FALLBACK, engine.mode)
        assertEquals(2, sent.size)
        sent.clear()
        engine.onFrame(frame(0xFF0000, 0x0000FF), 2_200)
        assertTrue("im App-Ersatzmodus kein Ambilight", sent.isEmpty())
    }

    @Test
    fun appProfileStandardUsesStandardLight() {
        connectWithSnapshot()
        engine.onFrame(frame(0xFF0000, 0x0000FF), 2_000)
        sent.clear()
        engine.setAppAction(AppAction.STANDARD, 2_100)
        assertEquals(Mode.APP_STANDARD, engine.mode)
        assertTrue(sentTo(leftSet).single().contains("\"brightness\":150"))
        assertTrue(sentTo(rightSet).single().contains("\"x\":0.6"))
        sent.clear()
        engine.setAppAction(AppAction.AMBILIGHT, 3_000)
        engine.onFrame(frame(0xFF0000, 0x0000FF), 3_100)
        assertEquals(2, sent.size)
    }

    @Test
    fun haPauseThenResumeTakesNewSnapshot() {
        connectWithSnapshot()
        engine.setHaPaused(true, 2_000)
        sent.clear()
        engine.setHaPaused(false, 3_000)
        assertEquals(listOf("z2m/L/get", "z2m/R/get"), sent.map { it.first })
    }

    @Test
    fun wakeShowsStandardLightUntilPlayback() {
        connectWithSnapshot()
        engine.setMediaAvailable(true, 1_500)
        engine.startSession(2_000)
        assertEquals(Mode.STANDARD, engine.mode)
        assertEquals(2, sent.size)
        sent.clear()
        engine.onFrame(frame(0xFF0000, 0x0000FF), 2_200)
        assertTrue("Standardlicht hält bis zur Wiedergabe", sent.isEmpty())
        engine.setPlayback(Playback.PLAYING, 3_000)
        assertEquals(Mode.ACTIVE, engine.mode)
        engine.onFrame(frame(0xFF0000, 0x0000FF), 3_100)
        assertEquals(2, sent.size)
    }

    @Test
    fun wakeHoldCanBeEndedEarly() {
        connectWithSnapshot()
        engine.setMediaAvailable(true, 1_500)
        engine.startSession(2_000)
        assertEquals(Mode.STANDARD, engine.mode)
        engine.endWakeHold(2_500)
        assertEquals(Mode.ACTIVE, engine.mode)
    }

    @Test
    fun wakeWithoutMediaDetectionStartsAmbilightDirectly() {
        connectWithSnapshot()
        engine.startSession(2_000)
        assertEquals(Mode.ACTIVE, engine.mode)
    }

    @Test
    fun screenOnAfterStandbyShowsStandardLight() {
        connectWithSnapshot()
        engine.setMediaAvailable(true, 1_500)
        engine.setPlayback(Playback.PLAYING, 1_600)
        engine.setScreenOn(false, 2_000)
        engine.setPlayback(Playback.NONE, 2_100)
        engine.setScreenOn(true, 5_000)
        assertEquals(Mode.STANDARD, engine.mode)
    }

    @Test
    fun pauseLightAfterDelayAndBackOnPlay() {
        connectWithSnapshot()
        engine.setMediaAvailable(true, 1_500)
        engine.setPlayback(Playback.PLAYING, 1_600)
        engine.onFrame(frame(0xFF0000, 0x0000FF), 2_000)
        sent.clear()
        engine.setPlayback(Playback.PAUSED, 3_000)
        engine.tick(3_500)
        assertEquals("kurze Pause (Spulen) löst nichts aus", Mode.ACTIVE, engine.mode)
        engine.tick(4_100)
        assertEquals(Mode.PAUSE_LIGHT, engine.mode)
        assertTrue(sentTo(leftSet).single().contains("\"brightness\":230"))
        sent.clear()
        engine.setPlayback(Playback.PLAYING, 6_000)
        assertEquals(Mode.ACTIVE, engine.mode)
    }

    @Test
    fun pauseBeatsAppFallback() {
        connectWithSnapshot()
        engine.setMediaAvailable(true, 1_500)
        engine.setPlayback(Playback.PLAYING, 1_600)
        engine.setAppAction(AppAction.FALLBACK, 2_000)
        assertEquals(Mode.APP_FALLBACK, engine.mode)
        engine.setPlayback(Playback.PAUSED, 3_000)
        engine.tick(4_100)
        assertEquals(Mode.PAUSE_LIGHT, engine.mode)
        engine.setPlayback(Playback.PLAYING, 5_000)
        assertEquals(Mode.APP_FALLBACK, engine.mode)
    }

    @Test
    fun pauseActionAmbilightIgnoresPause() {
        connectWithSnapshot()
        engine.updateConfig(config.copy(pauseAction = PauseAction.AMBILIGHT), 1_000)
        engine.setMediaAvailable(true, 1_500)
        engine.setPlayback(Playback.PAUSED, 2_000)
        engine.tick(5_000)
        assertEquals(Mode.ACTIVE, engine.mode)
    }

    @Test
    fun stopWithStandardLight() {
        connectWithSnapshot()
        val final = engine.finalMessages(LightAction.STANDARD)
        assertTrue(final.all { it.second.contains("\"brightness\":150") })
    }

    @Test
    fun standbyRestoresAndStopMessages() {
        connectWithSnapshot()
        engine.onFrame(frame(0xFF0000, 0x0000FF), 2_000)
        sent.clear()
        engine.setScreenOn(false, 2_100)
        assertEquals(Mode.STANDBY, engine.mode)
        assertTrue(sentTo(leftSet).single().contains("color_temp"))
        val final = engine.finalMessages(LightAction.OFF)
        assertEquals(listOf(leftSet, rightSet), final.map { it.first })
        assertTrue(final.all { it.second.contains("\"state\":\"OFF\"") })
    }

    @Test
    fun haPauseSendsNothingAndStopLeavesLightsAlone() {
        connectWithSnapshot()
        engine.setHaPaused(true, 2_000)
        engine.onFrame(frame(0xFF0000, 0x0000FF), 2_200)
        assertTrue(sent.isEmpty())
        assertTrue(engine.finalMessages(LightAction.RESTORE).isEmpty())
        assertEquals(Mode.PAUSED, modes.last())
    }

    @Test
    fun latencyIsMeasuredFromStateEcho() {
        connectWithSnapshot()
        engine.collectStats(1_900)
        engine.onFrame(frame(0xFF0000, 0x0000FF), 2_000)
        engine.onLightState("z2m/L", """{"state":"ON"}""", 2_080)
        engine.onLightState("z2m/R", """{"state":"ON"}""", 2_120)
        val s = engine.collectStats(3_900)
        assertEquals(100, s.latencyAvgMs)
        assertEquals(120, s.latencyMaxMs)
        assertTrue(s.zigbeePerSec > 0f)
    }

    @Test
    fun appProfilesPriority() {
        val p = AppProfiles(fallback = setOf("a", "b"), standard = setOf("b", "c"), off = setOf("c"))
        assertEquals(AppAction.FALLBACK, p.actionFor("a"))
        assertEquals(AppAction.STANDARD, p.actionFor("b"))
        assertEquals(AppAction.OFF, p.actionFor("c"))
        assertEquals(AppAction.AMBILIGHT, p.actionFor("x"))
        assertEquals(AppAction.AMBILIGHT, p.actionFor(null))
    }
}
