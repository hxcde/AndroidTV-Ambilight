package io.github.hxcde.ambilight.ui

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import io.github.hxcde.ambilight.AppSettings
import io.github.hxcde.ambilight.BuildConfig
import io.github.hxcde.ambilight.R
import io.github.hxcde.ambilight.mqtt.MqttConnection
import io.github.hxcde.ambilight.service.AmbilightService
import io.github.hxcde.ambilight.service.AmbilightState
import io.github.hxcde.ambilight.service.Mode
import io.github.hxcde.ambilight.service.Status
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private lateinit var startStop: Button
    private lateinit var modeText: TextView
    private lateinit var mqttText: TextView
    private lateinit var testResult: TextView
    private lateinit var swatchLeft: android.view.View
    private lateinit var swatchRight: android.view.View
    private var running = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        startStop = findViewById(R.id.button_start_stop)
        modeText = findViewById(R.id.text_mode)
        mqttText = findViewById(R.id.text_mqtt)
        testResult = findViewById(R.id.text_test_result)
        swatchLeft = findViewById(R.id.swatch_left)
        swatchRight = findViewById(R.id.swatch_right)
        findViewById<TextView>(R.id.text_version).text = getString(R.string.version, BuildConfig.VERSION_NAME)

        startStop.setOnClickListener {
            if (running) {
                AmbilightService.stop(this)
            } else if (!AppSettings(this).isConfigured) {
                openSettings()
            } else {
                startActivity(Intent(this, CaptureRequestActivity::class.java))
            }
        }
        findViewById<Button>(R.id.button_settings).setOnClickListener { openSettings() }
        findViewById<Button>(R.id.button_test).setOnClickListener { runConnectionTest() }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                AmbilightState.status.collect { render(it) }
            }
        }
        startStop.requestFocus()
    }

    private fun render(s: Status) {
        running = s.mode != Mode.STOPPED
        startStop.setText(if (running) R.string.action_stop else R.string.action_start)
        modeText.text = getString(R.string.status_mode, s.mode.label)
        mqttText.text = when {
            !running -> getString(R.string.status_mqtt, "–")
            s.mqttConnected -> getString(R.string.status_mqtt, getString(R.string.mqtt_connected))
            s.mqttError != null -> getString(R.string.status_mqtt, getString(R.string.mqtt_error, s.mqttError))
            else -> getString(R.string.status_mqtt, getString(R.string.mqtt_connecting))
        }
        ViewCompat.setBackgroundTintList(swatchLeft, android.content.res.ColorStateList.valueOf(s.leftColor))
        ViewCompat.setBackgroundTintList(swatchRight, android.content.res.ColorStateList.valueOf(s.rightColor))
    }

    private fun openSettings() = startActivity(Intent(this, SettingsActivity::class.java))

    /** Verbindet sich einmal mit dem Broker und lässt links rot, rechts blau leuchten. */
    private fun runConnectionTest() {
        val settings = AppSettings(this)
        if (settings.mqttHost.isEmpty()) {
            testResult.setText(R.string.test_no_host)
            return
        }
        testResult.setText(R.string.test_running)
        val base = settings.baseTopic
        val messages = buildList {
            if (settings.lightLeft.isNotEmpty()) {
                add("$base/${settings.lightLeft}/set" to """{"state":"ON","brightness":200,"color":{"x":0.675,"y":0.322},"transition":0.5}""")
            }
            if (settings.lightRight.isNotEmpty()) {
                add("$base/${settings.lightRight}/set" to """{"state":"ON","brightness":200,"color":{"x":0.167,"y":0.04},"transition":0.5}""")
            }
        }
        lifecycleScope.launch {
            val error = withContext(Dispatchers.IO) { MqttConnection.testOnce(settings.mqttConfig(), messages) }
            testResult.text = when {
                error != null -> getString(R.string.test_failed, error)
                messages.isEmpty() -> getString(R.string.test_ok_no_lights)
                else -> getString(R.string.test_ok)
            }
        }
    }
}
