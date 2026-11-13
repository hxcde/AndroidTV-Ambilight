package io.github.hxcde.ambilight.service

import android.app.Activity
import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.PackageManager
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
import androidx.core.content.edit
import androidx.preference.PreferenceManager
import io.github.hxcde.ambilight.AppSettings
import io.github.hxcde.ambilight.BuildConfig
import io.github.hxcde.ambilight.R
import io.github.hxcde.ambilight.capture.FrameAnalyzer
import io.github.hxcde.ambilight.engine.AmbilightEngine
import io.github.hxcde.ambilight.engine.AppAction
import io.github.hxcde.ambilight.engine.AppProfiles
import io.github.hxcde.ambilight.engine.EngineOutput
import io.github.hxcde.ambilight.engine.Mode
import io.github.hxcde.ambilight.engine.Playback
import io.github.hxcde.ambilight.light.FixedColor
import io.github.hxcde.ambilight.light.LightOutput
import io.github.hxcde.ambilight.mqtt.HaEntities
import io.github.hxcde.ambilight.mqtt.HaEntity
import io.github.hxcde.ambilight.mqtt.HaLight
import io.github.hxcde.ambilight.mqtt.HaNumber
import io.github.hxcde.ambilight.mqtt.HaSelect
import io.github.hxcde.ambilight.mqtt.HaSwitch
import io.github.hxcde.ambilight.mqtt.HomeAssistant
import io.github.hxcde.ambilight.mqtt.MqttConnection
import io.github.hxcde.ambilight.mqtt.MqttListener
import io.github.hxcde.ambilight.mqtt.MqttPackets
import io.github.hxcde.ambilight.ui.CaptureRequestActivity
import io.github.hxcde.ambilight.ui.MainActivity
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Vordergrunddienst: Bildschirmaufnahme in kleiner Auflösung, App-Erkennung, MQTT und Home Assistant.
 * Die eigentliche Lichtlogik steckt in [AmbilightEngine]. Alles außer den Android-Callbacks läuft auf [handler].
 */
class AmbilightService : Service(), SharedPreferences.OnSharedPreferenceChangeListener {

    private lateinit var settings: AppSettings
    private lateinit var prefs: SharedPreferences
    private lateinit var worker: HandlerThread
    private lateinit var handler: Handler
    private lateinit var appDetector: ForegroundAppDetector
    private lateinit var mediaWatcher: MediaWatcher

    private var mqtt: MqttConnection? = null
    private var ha: HomeAssistant? = null
    private var engine: AmbilightEngine? = null
    private var lightKey = ""

    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var releasingProjection = false

    private val analyzer = FrameAnalyzer()
    private var frameBuffer = ByteArray(0)
    private var zoneFraction = 0.3f
    private var lastAnalysis = 0L

    // Zustand (nur Worker-Thread)
    private var initialized = false
    private var screenOn = true
    private var haPaused = false
    private var profiles = AppProfiles()
    private var currentApp: String? = null
    private var mediaAvailable = false
    private var playback = Playback.NONE
    private var statsRound = 0
    private var liveLeft = 0
    private var liveRight = 0
    private var livePublished = ""
    private var liveRound = 0

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
            engine?.tick(SystemClock.elapsedRealtime())
            handler.postDelayed(this, 500)
        }
    }

    private val statsLoop = object : Runnable {
        override fun run() {
            publishStats()
            handler.postDelayed(this, STATS_INTERVAL_MS)
        }
    }

    private val appLoop = object : Runnable {
        override fun run() {
            pollForegroundApp()
            pollMedia()
            if (++liveRound % 2 == 0) publishLive()
            handler.postDelayed(this, 1_000)
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
        prefs = PreferenceManager.getDefaultSharedPreferences(this)
        settings = AppSettings(prefs)
        appDetector = ForegroundAppDetector(this)
        zoneFraction = settings.zoneFraction
        profiles = settings.appProfiles

        worker = HandlerThread("ambilight-worker").also { it.start() }
        handler = Handler(worker.looper)
        mediaWatcher = MediaWatcher(this, handler) { updatePlayback() }
        handler.post { connect(rebuildEngine = true) }

        prefs.registerOnSharedPreferenceChangeListener(this)
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
        }
        ContextCompat.registerReceiver(this, screenReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        handler.postDelayed(tick, 500)
        handler.postDelayed(statsLoop, STATS_INTERVAL_MS)
        handler.post(appLoop)
    }

    // ---------------------------------------------------------------- Verbindung

    /** Baut MQTT (und bei geänderten Lampen die Engine) neu auf. Nur Worker-Thread. */
    private fun connect(rebuildEngine: Boolean) {
        val now = SystemClock.elapsedRealtime()
        if (rebuildEngine || engine == null) {
            lightKey = "${settings.baseTopic}|${settings.lightLeft}|${settings.lightRight}"
            engine = AmbilightEngine(
                LightOutput(settings.lightLeft, settings.baseTopic),
                LightOutput(settings.lightRight, settings.baseTopic),
                settings.engineConfig,
                engineOutput,
            ).also {
                it.setScreenOn(screenOn, now)
                it.setHaPaused(haPaused, now)
                it.setAppAction(currentAppAction(), now)
                it.setMediaAvailable(mediaAvailable, now)
                it.setPlayback(playback, now)
            }
        }
        val e = engine!!
        val h = HomeAssistant(settings.appTopic, settings.haPrefix, settings.clientId, BuildConfig.VERSION_NAME)
        ha = h
        val will = MqttPackets.Will(h.statusTopic, "offline".toByteArray(), retain = true)
        mqtt = MqttConnection(settings.mqttConfig(will), mqttListener) { Log.i(TAG, it) }.also {
            it.start(listOf(h.setTopic, h.birthTopic, h.configSetFilter, h.commandTopic) + e.outputs.map { o -> o.stateTopic })
        }
        AmbilightState.update { it.copy(mqttConnected = false, mqttError = null) }
    }

    /** Verbindungsdaten geändert: alte Verbindung sauber beenden, neue aufbauen. */
    private fun reconnect() {
        val newLightKey = "${settings.baseTopic}|${settings.lightLeft}|${settings.lightRight}"
        val rebuild = newLightKey != lightKey
        val final = ArrayList<Triple<String, String, Boolean>>()
        if (rebuild) engine?.finalMessages(settings.stopAction)?.forEach { (t, p) -> final += Triple(t, p, false) }
        ha?.let { final += Triple(it.statusTopic, "offline", true) }
        mqtt?.stop(final)
        mqtt = null
        Log.i(TAG, "Einstellungen geändert, verbinde neu")
        connect(rebuild)
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
            handler.post { onMqttMessage(topic, payload) }
        }
    }

    private val engineOutput = object : EngineOutput {
        override fun publish(topic: String, payload: String) {
            mqtt?.publishLatest(topic, payload)
        }

        override fun modeChanged(mode: Mode) {
            AmbilightState.update { it.copy(mode = mode) }
            ha?.let { mqtt?.publish(it.modeTopic, mode.label, retain = true) }
            updateNotification(mode)
        }

        override fun preview(left: Int, right: Int) {
            liveLeft = left
            liveRight = right
            AmbilightState.update { it.copy(leftColor = left, rightColor = right) }
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
        engine?.startSession(SystemClock.elapsedRealtime())
        engine?.let { AmbilightState.update { s -> s.copy(mode = it.mode) } }
        updateNotification(engine?.mode ?: Mode.ACTIVE)
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
            if (now - lastAnalysis < ANALYSIS_INTERVAL_MS) return
            lastAnalysis = now
            val plane = image.planes[0]
            val buf = plane.buffer
            buf.rewind()
            val size = buf.remaining()
            if (frameBuffer.size != size) frameBuffer = ByteArray(size)
            buf.get(frameBuffer, 0, size)
            val result = analyzer.analyze(frameBuffer, image.width, image.height, plane.rowStride, plane.pixelStride, zoneFraction)
            engine?.onFrame(result, now)
        } catch (e: Exception) {
            Log.w(TAG, "Bild konnte nicht verarbeitet werden", e)
        } finally {
            image.close()
        }
    }

    private fun onProjectionStopped(stopped: MediaProjection) {
        if (releasingProjection || stopped !== projection) return
        Log.i(TAG, "Bildaufnahme wurde vom System beendet")
        releaseProjection()
        if (!screenOn && settings.autostart) {
            Log.i(TAG, "Warte auf Einschalten, um die Freigabe neu anzufragen")
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

    private fun onScreen(on: Boolean) {
        if (screenOn == on) return
        screenOn = on
        Log.i(TAG, if (on) "TV eingeschaltet" else "TV im Standby")
        engine?.setScreenOn(on, SystemClock.elapsedRealtime())
        if (on && projection == null) {
            if (settings.autostart) requestCaptureAgain() else stopSelf()
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

    // ---------------------------------------------------------------- App-Erkennung

    private fun currentAppAction(): AppAction =
        if (settings.appDetection) profiles.actionFor(currentApp) else AppAction.AMBILIGHT

    private fun pollForegroundApp() {
        if (!settings.appDetection) {
            if (currentApp != null) {
                currentApp = null
                engine?.setAppAction(AppAction.AMBILIGHT, SystemClock.elapsedRealtime())
            }
            AmbilightState.update { it.copy(appDetection = null, activeApp = null) }
            return
        }
        if (!appDetector.hasPermission()) {
            AmbilightState.update { it.copy(appDetection = false, activeApp = null) }
            return
        }
        val pkg = appDetector.poll()
        if (pkg == currentApp) {
            AmbilightState.update { it.copy(appDetection = true) }
            return
        }
        currentApp = pkg
        val label = appDetector.label(pkg)
        Log.i(TAG, "App im Vordergrund: $pkg")
        engine?.setAppAction(profiles.actionFor(pkg), SystemClock.elapsedRealtime())
        AmbilightState.update { it.copy(appDetection = true, activeApp = label) }
        ha?.let { mqtt?.publish(it.activeAppTopic, label ?: "", retain = true) }
    }

    // ---------------------------------------------------------------- Wiedergabe / Pause

    private fun pollMedia() {
        val available = mediaWatcher.ensureStarted()
        if (available != mediaAvailable) {
            mediaAvailable = available
            engine?.setMediaAvailable(available, SystemClock.elapsedRealtime())
            AmbilightState.update { it.copy(mediaDetection = available) }
        }
        updatePlayback()
    }

    private fun updatePlayback() {
        val fg = if (settings.appDetection && appDetector.hasPermission()) currentApp else null
        // App ohne Mediensitzung (z. B. Live-TV) geöffnet: nicht auf Wiedergabe warten, Ambilight sofort
        if (fg != null && fg != packageName && fg !in appDetector.launchers && !mediaWatcher.hasSession(fg)) {
            engine?.endWakeHold(SystemClock.elapsedRealtime())
        }
        val p = mediaWatcher.state(fg)
        if (p == playback) return
        playback = p
        Log.i(TAG, "Wiedergabe: ${p.label}")
        engine?.setPlayback(p, SystemClock.elapsedRealtime())
        AmbilightState.update { it.copy(playback = p) }
        ha?.let { mqtt?.publish(it.playbackTopic, p.label, retain = true) }
    }

    // ---------------------------------------------------------------- Einstellungen live

    override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences?, key: String?) {
        handler.post { applySetting(key) }
    }

    private fun applySetting(key: String?) {
        if (key == AppSettings.K_CLIENT_ID) return
        if (key == null || key in AppSettings.CONNECTION_KEYS) {
            reconnect()
            return
        }
        val now = SystemClock.elapsedRealtime()
        zoneFraction = settings.zoneFraction
        profiles = settings.appProfiles
        engine?.updateConfig(settings.engineConfig, now)
        engine?.setAppAction(currentAppAction(), now)
        if (key == AppSettings.K_HA_DISCOVERY) publishDiscovery()
        HaEntities.affectedBy(key).forEach { publishEntityState(it) }
    }

    // ---------------------------------------------------------------- MQTT / Home Assistant

    private fun onMqttConnected() {
        livePublished = ""
        engine?.onConnected(SystemClock.elapsedRealtime())
        publishAll()
    }

    private fun publishAll() {
        val h = ha ?: return
        val m = mqtt ?: return
        m.publish(h.statusTopic, "online", retain = true)
        m.publish(h.stateTopic, if (haPaused) "OFF" else "ON", retain = true)
        m.publish(h.modeTopic, (engine?.mode ?: Mode.ACTIVE).label, retain = true)
        m.publish(h.activeAppTopic, appDetector.label(currentApp) ?: "", retain = true)
        m.publish(h.playbackTopic, playback.label, retain = true)
        HaEntities.ALL.forEach { publishEntityState(it) }
        publishDiscovery()
    }

    private fun publishDiscovery() {
        val h = ha ?: return
        val m = mqtt ?: return
        val enabled = settings.haDiscovery
        // Leerer, gehaltener Payload entfernt die Entitäten wieder aus Home Assistant
        h.discovery().forEach { (topic, payload) -> m.publish(topic, if (enabled) payload else "", retain = true) }
        h.legacyConfigTopics().forEach { m.publish(it, "", retain = true) }
    }

    private fun publishEntityState(e: HaEntity) {
        val h = ha ?: return
        val value = when (e) {
            is HaNumber -> prefs.getInt(e.prefKey, e.default).toString()
            is HaSelect -> e.label(prefs.getString(e.prefKey, e.default))
            is HaSwitch -> if (prefs.getBoolean(e.prefKey, e.default)) "ON" else "OFF"
            is HaLight -> e.state(settings.resolveColor(e.colorKey, e.customKey), prefs.getInt(e.brightnessKey, e.brightnessDefault))
        }
        mqtt?.publish(h.configStateTopic(e.id), value, retain = true)
    }

    /** Aktuelle Lampenfarben an Home Assistant (höchstens alle 2 s, nur bei Änderung). */
    private fun publishLive() {
        val h = ha ?: return
        fun rgb(c: Int) = "[${(c shr 16) and 0xFF},${(c shr 8) and 0xFF},${c and 0xFF}]"
        val json = """{"left":"${FixedColor.hex(liveLeft)}","right":"${FixedColor.hex(liveRight)}",""" +
            """"left_rgb":${rgb(liveLeft)},"right_rgb":${rgb(liveRight)}}"""
        if (json == livePublished) return
        livePublished = json
        mqtt?.publish(h.liveTopic, json, retain = false)
    }

    private fun publishStats() {
        val e = engine ?: return
        val stats = e.collectStats(SystemClock.elapsedRealtime())
        val dropped = mqtt?.dropped?.get() ?: 0L
        AmbilightState.update { it.copy(stats = stats, dropped = dropped) }
        if (++statsRound % 5 != 0) return // an Home Assistant nur alle 10 s
        val h = ha ?: return
        val json = String.format(
            Locale.ROOT,
            """{"latency_ms":%s,"latency_max_ms":%s,"zigbee_per_s":%.1f,"messages_per_s":%.1f,"fps":%.1f,"dropped":%d}""",
            stats.latencyAvgMs?.toString() ?: "null", stats.latencyMaxMs?.toString() ?: "null",
            stats.zigbeePerSec, stats.messagesPerSec, stats.framesPerSec, dropped,
        )
        mqtt?.publish(h.statsTopic, json, retain = false)
    }

    private fun onMqttMessage(topic: String, raw: String) {
        val h = ha ?: return
        val payload = raw.trim()
        val now = SystemClock.elapsedRealtime()
        when {
            topic == h.setTopic -> when (payload.uppercase()) {
                "ON" -> setHaPaused(false)
                "OFF" -> setHaPaused(true)
            }
            topic == h.birthTopic -> if (payload == "online") publishAll()
            topic == h.commandTopic -> if (payload.uppercase() == "STOP") {
                Log.i(TAG, "Beenden per Home Assistant")
                stopSelf()
            }
            h.idFromSetTopic(topic) != null -> onEntityCommand(h.idFromSetTopic(topic)!!, payload)
            else -> engine?.onLightState(topic, payload, now)
        }
    }

    /** Befehl aus Home Assistant: Einstellung speichern; der Listener übernimmt sie live. */
    private fun onEntityCommand(id: String, payload: String) {
        val e = HaEntities.byId(id) ?: return
        when (e) {
            is HaNumber -> e.parse(payload)?.let { v -> prefs.edit { putInt(e.prefKey, v) } }
            is HaSelect -> e.parse(payload)?.let { v -> prefs.edit { putString(e.prefKey, v) } }
            is HaSwitch -> e.parse(payload)?.let { v -> prefs.edit { putBoolean(e.prefKey, v) } }
            is HaLight -> HaLight.parse(payload)?.let { c ->
                prefs.edit {
                    c.hex?.let { hex ->
                        if (e.customKey != null) {
                            putString(e.colorKey, FixedColor.CUSTOM)
                            putString(e.customKey, hex)
                        } else {
                            putString(e.colorKey, hex)
                        }
                    }
                    c.brightness?.let { putInt(e.brightnessKey, it) }
                }
            }
        }
        // Gleicher Wert löst keinen Listener aus: Status trotzdem bestätigen
        publishEntityState(e)
    }

    private fun setHaPaused(value: Boolean) {
        haPaused = value
        ha?.let { mqtt?.publish(it.stateTopic, if (value) "OFF" else "ON", retain = true) }
        engine?.setHaPaused(value, SystemClock.elapsedRealtime())
    }

    // ---------------------------------------------------------------- Benachrichtigung

    private fun startInForeground() {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION else 0
        ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification(Mode.ACTIVE), type)
    }

    private fun buildNotification(mode: Mode): Notification {
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

    private fun updateNotification(mode: Mode) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        try {
            NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, buildNotification(mode))
        } catch (_: SecurityException) {
        }
    }

    // ---------------------------------------------------------------- Ende

    override fun onDestroy() {
        if (initialized) {
            try {
                unregisterReceiver(screenReceiver)
            } catch (_: IllegalArgumentException) {
            }
            prefs.unregisterOnSharedPreferenceChangeListener(this)
            val stopAction = settings.stopAction
            handler.removeCallbacksAndMessages(null)
            handler.post {
                mediaWatcher.stop()
                releaseProjection()
                val final = ArrayList<Triple<String, String, Boolean>>()
                engine?.finalMessages(stopAction)?.forEach { (t, p) -> final += Triple(t, p, false) }
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
        /** Bildanalyse mit ~15 Bildern/s; gesendet wird nur bei Bedarf (siehe Engine). */
        private const val ANALYSIS_INTERVAL_MS = 66L
        private const val STATS_INTERVAL_MS = 2_000L

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
