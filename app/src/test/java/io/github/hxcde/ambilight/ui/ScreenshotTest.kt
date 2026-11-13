package io.github.hxcde.ambilight.ui

import android.content.Context
import androidx.core.content.edit
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceManager
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.matcher.ViewMatchers.isRoot
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.captureRoboImage
import com.github.takahirom.roborazzi.captureScreenRoboImage
import io.github.hxcde.ambilight.AppSettings
import io.github.hxcde.ambilight.R
import io.github.hxcde.ambilight.service.AmbilightState
import io.github.hxcde.ambilight.service.Mode
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowLooper

/**
 * Erzeugt Screenshots der Oberfläche in TV-Auflösung (1920×1080).
 * Aufnehmen: ./gradlew recordRoborazziDebug  →  docs/screenshots/
 */
@OptIn(ExperimentalRoborazziApi::class)
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "de-w960dp-h540dp-land-television-xhdpi")
class ScreenshotTest {

    private val dir = "../docs/screenshots"

    @Before
    fun setUp() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        PreferenceManager.getDefaultSharedPreferences(ctx).edit {
            putString(AppSettings.K_HOST, "192.168.1.20")
            putString(AppSettings.K_USER, "ambilight")
            putString(AppSettings.K_PASSWORD, "beispiel")
            putString(AppSettings.K_LIGHT_LEFT, "Playbar links")
            putString(AppSettings.K_LIGHT_RIGHT, "Playbar rechts")
            putString(AppSettings.K_BLACK_COLOR_LEFT, "#8000FF")
            putString(AppSettings.K_BLACK_COLOR_RIGHT, "custom")
            putString(AppSettings.K_BLACK_COLOR_RIGHT_CUSTOM, "#2F6BFF")
        }
        AmbilightState.reset()
    }

    @After
    fun tearDown() = AmbilightState.reset()

    @Test
    fun start_gestoppt() {
        ActivityScenario.launch(MainActivity::class.java).use {
            idle()
            onView(isRoot()).captureRoboImage("$dir/01_start_gestoppt.png")
        }
    }

    @Test
    fun start_aktiv() {
        AmbilightState.update {
            it.copy(mode = Mode.ACTIVE, mqttConnected = true, leftColor = 0xFFE0702A.toInt(), rightColor = 0xFF2A8FE0.toInt())
        }
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            idle()
            focus(scenario, R.id.button_start_stop)
            onView(isRoot()).captureRoboImage("$dir/02_start_aktiv.png")
        }
    }

    @Test
    fun start_geschuetzt() {
        AmbilightState.update {
            it.copy(mode = Mode.PROTECTED, mqttConnected = true, leftColor = 0xFF8000FF.toInt(), rightColor = 0xFF2F6BFF.toInt())
        }
        ActivityScenario.launch(MainActivity::class.java).use {
            idle()
            onView(isRoot()).captureRoboImage("$dir/03_start_geschuetzter_inhalt.png")
        }
    }

    @Test
    fun einstellungen() {
        ActivityScenario.launch(SettingsActivity::class.java).use { scenario ->
            idle()
            onView(isRoot()).captureRoboImage("$dir/04_einstellungen_mqtt.png")
            scrollTo(scenario, AppSettings.K_SMOOTHING)
            onView(isRoot()).captureRoboImage("$dir/05_einstellungen_bild.png")
            scrollTo(scenario, AppSettings.K_BLACK_BRIGHTNESS)
            onView(isRoot()).captureRoboImage("$dir/06_einstellungen_ersatzfarben.png")
            scrollTo(scenario, AppSettings.K_APP_TOPIC)
            onView(isRoot()).captureRoboImage("$dir/07_einstellungen_home_assistant.png")
        }
    }

    @Test
    fun verbindungstest() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            idle()
            scenario.onActivity { it.findViewById<android.widget.TextView>(R.id.text_test_result).setText(R.string.test_ok) }
            focus(scenario, R.id.button_test)
            onView(isRoot()).captureRoboImage("$dir/08_verbindungstest.png")
        }
    }

    @Test
    fun dialog_ersatzmodus() = dialog(AppSettings.K_BLACK_MODE, "09_dialog_ersatzmodus.png")

    @Test
    fun dialog_farbauswahl() = dialog(AppSettings.K_BLACK_COLOR_LEFT, "10_dialog_farbauswahl.png")

    private fun dialog(key: String, file: String) {
        ActivityScenario.launch(SettingsActivity::class.java).use { scenario ->
            idle()
            scrollTo(scenario, key)
            scenario.onActivity { activity ->
                val fragment = activity.supportFragmentManager.findFragmentById(R.id.settings_container) as PreferenceFragmentCompat
                fragment.findPreference<androidx.preference.Preference>(key)!!.performClick()
            }
            idle()
            captureScreenRoboImage("$dir/$file")
        }
    }

    /** Fokusrahmen wie mit der Fernbedienung sichtbar machen (Robolectric läuft im Touch-Modus). */
    private fun focus(scenario: ActivityScenario<MainActivity>, id: Int) {
        scenario.onActivity {
            val v = it.findViewById<android.view.View>(id)
            v.isFocusableInTouchMode = true
            v.requestFocus()
        }
        idle()
    }

    private fun scrollTo(scenario: ActivityScenario<SettingsActivity>, key: String) {
        scenario.onActivity { activity ->
            val fragment = activity.supportFragmentManager.findFragmentById(R.id.settings_container) as PreferenceFragmentCompat
            fragment.scrollToPreference(key)
        }
        idle()
    }

    private fun idle() {
        repeat(3) { ShadowLooper.idleMainLooper() }
    }
}
