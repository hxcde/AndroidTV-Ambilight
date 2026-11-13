package io.github.hxcde.ambilight.service

import android.app.Activity
import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import android.view.Display
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import io.github.hxcde.ambilight.AppSettings
import io.github.hxcde.ambilight.BlackMode
import io.github.hxcde.ambilight.BuildConfig
import io.github.hxcde.ambilight.R
import io.github.hxcde.ambilight.capture.ColorMath
import io.github.hxcde.ambilight.capture.FrameAnalyzer
import io.github.hxcde.ambilight.capture.FrameResult
import io.github.hxcde.ambilight.light.FixedColor
import io.github.hxcde.ambilight.light.LightOutput
import io.github.hxcde.ambilight.light.LightState
import io.github.hxcde.ambilight.light.PipelineConfig
import io.github.hxcde.ambilight.mqtt.HomeAssistant
import io.github.hxcde.ambilight.mqtt.MqttConnection
import io.github.hxcde.ambilight.mqtt.MqttListener
import io.github.hxcde.ambilight.mqtt.MqttPackets
import io.github.hxcde.ambilight.ui.CaptureRequestActivity
import io.github.hxcde.ambilight.ui.MainActivity
import kotlin.math.roundToInt

/**
 * Vordergrunddienst: nimmt den Bildschirm in sehr kleiner Auflösung auf, berechnet die
 * Farben der linken/rechten Randzone und sendet sie per MQTT an Zigbee2MQTT.
 *
 * Bildverarbeitung, Zustand und Lichtsteuerung laufen alle auf dem Worker-Thread [handler].
 */
class AmbilightService : Service() {

    private lateinit var settings: AppSettings
    private lateinit var worker: HandlerThread
    private lateinit var handler: Handler

    private var mqtt: MqttConnection? = null
    private var ha: HomeAssistant? = null
    private lateinit var left: LightOutput
    private lateinit var right: LightOutput

    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var releasingProjection = false

    private val analyzer = FrameAnalyzer()
    private var frameBuffer = ByteArray(0)

    // Konfiguration (beim Start gelesen)
    private lateinit var cfg: PipelineConfig
    private var intervalMs = 200L
    private var transitionSec = 0.2
    private var zoneFraction = 0.3f
    private var blackMode = BlackMode.COLOR
    private var blackBrightness = 80
    private var blackColorLeft = FixedColor.DEFAULT
    private var blackColorRight = FixedColor.DEFAULT
    private var blackDelayMs = 2_000L

    // Zustand (nur Worker-Thread)
    private var initialized = false
    private var paused = false
    private var screenOn = true
    private var protectedActive = false
    private var blackSince = 0L
    private var lastProcess = 0L
    private var mode = Mode.STOPPED

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_OFF -> handler.post { onScreen(false) }
                Intent.ACTION_SCREEN_ON -> handler.post { onScreen(true) }
            }
        }
    }

    private val tick = object : Runnable {
        override fun run() {
            checkBlackTimeout(SystemClock.elapsedRealtime())
            handler.postDelayed(this, 500)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED) ?: Activity.RESULT_CANCELED
        val data = intent?.let { IntentCompat.getParcelableExtra(it, EXTRA_DATA, Intent::class.java) }
        if (resultCode != Activity.RESULT_OK || data == null) {
            Log.w(TAG, "Start ohne gültige Bildschirmfreigabe")
            if (!initialized) stopSelf()
            return START_NOT_STICKY
        }

        // Android 14+: Vordergrunddienst muss vor getMediaProjection() laufen.
        startInForeground()

        if (!initialized) init()

        val mpm = getSystemService(MediaProjectionManager::class.java)
        val newProjection = try {
            mpm.getMediaProjection(resultCode, data)
        } catch (e: SecurityException) {
            Log.e(TAG, "Bildschirmfreigabe ungültig", e)
            null
        }
        if (newProjection == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        handler.post { startCapture(newProjection) }
        return START_NOT_STICKY
    }

    private fun init() {
        initialized = true
        settings = AppSettings(this)
        cfg = settings.pipeline
        intervalMs = 1000L / settings.updateRate
        transitionSec = settings.transitionSec
        zoneFraction = settings.zoneFraction
        blackMode = settings.blackMode
        blackBrightness = settings.blackBrightness
        blackColorLeft = settings.blackColorLeft
        blackColorRight = settings.blackColorRight
        blackDelayMs = settings.blackDelayMs

        left = LightOutput(settings.lightLeft, settings.baseTopic)
        right = LightOutput(settings.lightRight, settings.baseTopic)

        worker = HandlerThread("ambilight-worker").also { it.start() }
        handler = Handler(worker.looper)

        val haInfo = HomeAssistant(settings.appTopic, settings.haPrefix, settings.clientId, BuildConfig.VERSION_NAME)
        ha = haInfo
        val will = MqttPackets.Will(haInfo.statusTopic, "offline".toByteArray(), retain = true)
        mqtt = MqttConnection(settings.mqttConfig(will), mqttListener) { Log.i(TAG, it) }.also {
            it.start(listOf(haInfo.setTopic, haInfo.birthTopic))
        }

        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
        }
        ContextCompat.registerReceiver(this, screenReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        handler.postDelayed(tick, 500)
    }

    private val mqttListener = object : MqttListener {
        override fun onConnected() {
            AmbilightState.update { it.copy(mqttConnected = true, mqttError = null) }
            handler.post { onMqttConnected() }
        }

        override fun onDisconnected(reason: String) {
            AmbilightState.update { it.copy(mqttConnected = false, mqttError = reason) }
        }

        override fun onMessage(topic: String, payload: String) {
            handler.post { onMqttMessage(topic, payload.trim()) }
        }
    }

    // ---------------------------------------------------------------- Bildaufnahme

    private fun startCapture(newProjection: MediaProjection) {
        releaseProjection()
        projection = newProjection
        newProjection.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                handler.post { onProjectionStopped(newProjection) }
            }
        }, handler)

        val (w, h) = captureSize()
        val reader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2)
        reader.setOnImageAvailableListener({ onImage(it) }, handler)
        imageReader = reader
        virtualDisplay = try {
            newProjection.createVirtualDisplay(
                "ambilight", w, h, resources.displayMetrics.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, reader.surface, null, handler,
            )
        } catch (e: Exception) {
            Log.e(TAG, "VirtualDisplay fehlgeschlagen", e)
            stopSelf()
            return
        }
        Log.i(TAG, "Aufnahme läuft mit ${w}x$h")
        resetLights()
        setMode(if (paused) Mode.PAUSED else Mode.ACTIVE)
    }

    private fun captureSize(): Pair<Int, Int> {
        val display = getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)
        val m = display?.mode
        val aspect = if (m != null && m.physicalHeight > 0) m.physicalWidth.toFloat() / m.physicalHeight else 16f / 9f
        val w = CAPTURE_WIDTH
        val h = ((w / aspect).roundToInt() / 2 * 2).coerceIn(2, w)
        return w to h
    }

    private fun onImage(reader: ImageReader) {
        val image = try {
            reader.acquireLatestImage()
        } catch (e: IllegalStateException) {
            null
        } ?: return
        try {
            val now = SystemClock.elapsedRealtime()
            if (now - lastProcess < intervalMs) return
            lastProcess = now
            val plane = image.planes[0]
            val buf = plane.buffer
            buf.rewind()
            val size = buf.remaining()
            if (frameBuffer.size != size) frameBuffer = ByteArray(size)
            buf.get(frameBuffer, 0, size)
            val result = analyzer.analyze(frameBuffer, image.width, image.height, plane.rowStride, plane.pixelStride, zoneFraction)
            onFrame(result, now)
        } catch (e: Exception) {
            Log.w(TAG, "Bild konnte nicht verarbeitet werden", e)
        } finally {
            image.close()
        }
    }

    private fun onFrame(result: FrameResult, now: Long) {
        if (!screenOn || paused) return

        if (result.isBlack) {
            if (blackSince == 0L) blackSince = now
        } else {
            blackSince = 0L
            if (protectedActive) {
                protectedActive = false
                resetLights(invalidate = false)
                setMode(Mode.ACTIVE)
            }
        }
        if (protectedActive) return
        if (checkBlackTimeout(now)) return
        // Bis zum Umschalten in den Ersatzmodus letzte echte Farbe stehen lassen (nicht auf Schwarz abdunkeln)
        if (result.isBlack && blackMode != BlackMode.NORMAL) return

        val lt = left.target(result.left, cfg)
        val rt = right.target(result.right, cfg)
        send(left, lt)
        send(right, rt)
        AmbilightState.update {
            it.copy(leftColor = ColorMath.toArgb(left.previewSrgb), rightColor = ColorMath.toArgb(right.previewSrgb))
        }
    }

    /** Aktiviert den Ersatzmodus, wenn das Bild lange genug komplett schwarz ist. */
    private fun checkBlackTimeout(now: Long): Boolean {
        if (protectedActive || blackSince == 0L || blackMode == BlackMode.NORMAL) return protectedActive
        if (!screenOn || paused || projection == null) return false
        if (now - blackSince < blackDelayMs) return false
        protectedActive = true
        setMode(Mode.PROTECTED)
        when (blackMode) {
            BlackMode.COLOR -> {
                send(left, LightState.fixed(blackColorLeft, blackBrightness), 1.0)
                send(right, LightState.fixed(blackColorRight, blackBrightness), 1.0)
                AmbilightState.update { it.copy(leftColor = blackColorLeft.previewArgb, rightColor = blackColorRight.previewArgb) }
            }
            BlackMode.OFF -> {
                send(left, LightState.OFF, 1.0)
                send(right, LightState.OFF, 1.0)
                AmbilightState.update { it.copy(leftColor = BLACK, rightColor = BLACK) }
            }
            BlackMode.HOLD, BlackMode.NORMAL -> Unit
        }
        return true
    }

    private fun onProjectionStopped(stopped: MediaProjection) {
        if (releasingProjection || stopped !== projection) return
        Log.i(TAG, "Bildaufnahme wurde vom System beendet")
        releaseProjection()
        if (!screenOn && settings.autostart) {
            setMode(Mode.STANDBY) // nach dem Einschalten neu anfragen
        } else {
            stopSelf()
        }
    }

    private fun releaseProjection() {
        releasingProjection = true
        try {
            virtualDisplay?.release()
            imageReader?.close()
            projection?.stop()
        } catch (e: Exception) {
            Log.w(TAG, "Fehler beim Freigeben", e)
        } finally {
            virtualDisplay = null
            imageReader = null
            projection = null
            releasingProjection = false
        }
    }

    // ---------------------------------------------------------------- Licht

    private fun send(output: LightOutput, target: LightState, transition: Double = transitionSec, force: Boolean = false) {
        val payload = output.payloadFor(target, cfg, transition, force) ?: return
        mqtt?.publishLatest(output.setTopic, payload)
    }

    private fun resetLights(invalidate: Boolean = true) {
        blackSince = 0L
        protectedActive = false
        left.resetSmoothing()
        right.resetSmoothing()
        if (invalidate) {
            left.invalidate()
            right.invalidate()
        }
    }

    private fun onScreen(on: Boolean) {
        if (screenOn == on) return
        screenOn = on
        if (!on) {
            Log.i(TAG, "TV im Standby")
            if (settings.offOnStandby && !paused) {
                send(left, LightState.OFF, 1.0, force = true)
                send(right, LightState.OFF, 1.0, force = true)
            }
            setMode(Mode.STANDBY)
        } else {
            Log.i(TAG, "TV eingeschaltet")
            resetLights()
            if (projection != null) {
                setMode(if (paused) Mode.PAUSED else Mode.ACTIVE)
            } else if (settings.autostart) {
                requestCaptureAgain()
            } else {
                stopSelf()
            }
        }
    }

    private fun requestCaptureAgain() {
        try {
            startActivity(Intent(this, CaptureRequestActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) {
            Log.w(TAG, "Bildschirmfreigabe konnte nicht erneut angefragt werden", e)
            stopSelf()
        }
    }

    // ---------------------------------------------------------------- MQTT / Home Assistant

    private fun onMqttConnected() {
        left.invalidate()
        right.invalidate()
        val h = ha ?: return
        val m = mqtt ?: return
        m.publish(h.statusTopic, "online", retain = true)
        m.publish(h.stateTopic, if (paused) "OFF" else "ON", retain = true)
        m.publish(h.modeTopic, mode.label, retain = true)
        publishDiscovery()
    }

    private fun publishDiscovery() {
        val h = ha ?: return
        val m = mqtt ?: return
        if (settings.haDiscovery) {
            m.publish(h.switchConfigTopic, h.switchConfig(), retain = true)
            m.publish(h.sensorConfigTopic, h.sensorConfig(), retain = true)
        } else {
            // Leerer, gehaltener Payload entfernt die Entitäten wieder aus Home Assistant
            m.publish(h.switchConfigTopic, "", retain = true)
            m.publish(h.sensorConfigTopic, "", retain = true)
        }
    }

    private fun onMqttMessage(topic: String, payload: String) {
        val h = ha ?: return
        when (topic) {
            h.setTopic -> when (payload.uppercase()) {
                "ON" -> setPaused(false)
                "OFF" -> setPaused(true)
            }
            h.birthTopic -> if (payload == "online") {
                mqtt?.publish(h.statusTopic, "online", retain = true)
                mqtt?.publish(h.stateTopic, if (paused) "OFF" else "ON", retain = true)
                mqtt?.publish(h.modeTopic, mode.label, retain = true)
                publishDiscovery()
            }
        }
    }

    private fun setPaused(value: Boolean) {
        paused = value
        ha?.let { mqtt?.publish(it.stateTopic, if (value) "OFF" else "ON", retain = true) }
        if (value) {
            setMode(Mode.PAUSED)
        } else {
            resetLights()
            setMode(if (!screenOn) Mode.STANDBY else Mode.ACTIVE)
        }
    }

    private fun setMode(newMode: Mode) {
        if (mode == newMode) return
        mode = newMode
        AmbilightState.update { it.copy(mode = newMode) }
        ha?.let { mqtt?.publish(it.modeTopic, newMode.label, retain = true) }
        updateNotification()
    }

    // ---------------------------------------------------------------- Benachrichtigung

    private fun startInForeground() {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION else 0
        ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification(), type)
    }

    private fun buildNotification(): Notification {
        val nm = NotificationManagerCompat.from(this)
        nm.createNotificationChannel(
            NotificationChannelCompat.Builder(CHANNEL_ID, NotificationManagerCompat.IMPORTANCE_LOW)
                .setName(getString(R.string.notification_channel))
                .build(),
        )
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, AmbilightService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(mode.label)
            .setOngoing(true)
            .setContentIntent(open)
            .addAction(0, getString(R.string.action_stop), stop)
            .build()
    }

    private fun updateNotification() {
        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED || Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU
        ) {
            try {
                NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, buildNotification())
            } catch (_: SecurityException) {
            }
        }
    }

    // ---------------------------------------------------------------- Ende

    override fun onDestroy() {
        if (initialized) {
            try {
                unregisterReceiver(screenReceiver)
            } catch (_: IllegalArgumentException) {
            }
            val offOnStop = settings.offOnStop
            handler.removeCallbacksAndMessages(null)
            handler.post {
                releaseProjection()
                val final = ArrayList<Triple<String, String, Boolean>>()
                if (offOnStop && !paused) {
                    left.payloadFor(LightState.OFF, cfg, 1.0, force = true)?.let { final += Triple(left.setTopic, it, false) }
                    right.payloadFor(LightState.OFF, cfg, 1.0, force = true)?.let { final += Triple(right.setTopic, it, false) }
                }
                ha?.let {
                    final += Triple(it.modeTopic, Mode.STOPPED.label, true)
                    final += Triple(it.statusTopic, "offline", true)
                }
                mqtt?.stop(final)
                mqtt = null
            }
            worker.quitSafely()
        }
        AmbilightState.reset()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "Ambilight"
        private const val CHANNEL_ID = "ambilight"
        private const val NOTIFICATION_ID = 1
        private const val CAPTURE_WIDTH = 160
        private const val BLACK = 0xFF000000.toInt()

        const val ACTION_START = "io.github.hxcde.ambilight.START"
        const val ACTION_STOP = "io.github.hxcde.ambilight.STOP"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_DATA = "data"

        fun start(context: Context, resultCode: Int, data: Intent) {
            val intent = Intent(context, AmbilightService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_RESULT_CODE, resultCode)
                .putExtra(EXTRA_DATA, data)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, AmbilightService::class.java))
        }
    }
}
