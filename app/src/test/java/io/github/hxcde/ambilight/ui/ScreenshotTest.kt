package io.github.hxcde.ambilight.ui

import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.ResolveInfo
import androidx.core.content.edit
import androidx.preference.Preference
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
import io.github.hxcde.ambilight.engine.EngineStats
import io.github.hxcde.ambilight.engine.Mode
import io.github.hxcde.ambilight.service.AmbilightState
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowLooper

/**
 * Erzeugt Screenshots der Oberfläche in TV-Auflösung (1920×1080) mit Beispieldaten.
 * Aufnehmen: ./gradlew recordRoborazziDebug  →  docs/screenshots/
 */
@OptIn(ExperimentalRoborazziApi::class)
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "de-w960dp-h540dp-land-television-xhdpi")
class ScreenshotTest {

    private val dir = "../docs/screenshots"

    private val stats = EngineStats(framesPerSec = 15f, messagesPerSec = 6.4f, zigbeePerSec = 8.9f, latencyAvgMs = 84, latencyMaxMs = 152)

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
            putStringSet(AppSettings.K_APPS_FALLBACK, setOf("com.netflix.ninja", "com.amazon.amazonvideo.livingroom", "com.disney.disneyplus"))
            putStringSet(AppSettings.K_APPS_RESTORE, setOf("com.google.android.tvlauncher"))
        }
        // Beispiel-Apps, wie sie am TV installiert wären
        val pm = shadowOf(ctx.packageManager)
        listOf(
            "com.netflix.ninja" to "Netflix",
            "com.amazon.amazonvideo.livingroom" to "Prime Video",
            "com.disney.disneyplus" to "Disney+",
            "com.plexapp.android" to "Plex",
            "com.google.android.youtube.tv" to "YouTube",
            "com.google.android.tvlauncher" to "Startseite",
            "com.android.tv.settings" to "Einstellungen",
        ).forEach { (pkg, label) ->
            val ri = ResolveInfo().apply {
                activityInfo = ActivityInfo().apply {
                    packageName = pkg
                    name = "$pkg.Main"
                    applicationInfo = ApplicationInfo().apply { packageName = pkg }
                }
                nonLocalizedLabel = label
            }
            pm.addResolveInfoForIntent(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER), ri)
        }
        AmbilightState.reset()
    }

    @After
    fun tearDown() = AmbilightState.reset()

    @Test
    fun start_gestoppt() = main("01_start_gestoppt.png")

    @Test
    fun start_aktiv() {
        AmbilightState.update {
            it.copy(
                mode = Mode.ACTIVE, mqttConnected = true, leftColor = 0xFFE0702A.toInt(), rightColor = 0xFF2A8FE0.toInt(),
                stats = stats, appDetection = true, activeApp = "Plex",
            )
        }
        main("02_start_aktiv.png", focusId = R.id.button_start_stop)
    }

    @Test
    fun start_app_ersatzmodus() {
        AmbilightState.update {
            it.copy(
                mode = Mode.APP_FALLBACK, mqttConnected = true, leftColor = 0xFF8000FF.toInt(), rightColor = 0xFF2F6BFF.toInt(),
                stats = stats.copy(framesPerSec = 15f, messagesPerSec = 0f, zigbeePerSec = 0f), appDetection = true, activeApp = "Netflix",
            )
        }
        main("03_start_app_ersatzmodus.png")
    }

    @Test
    fun verbindungstest() = main("09_verbindungstest.png", focusId = R.id.button_test) {
        it.findViewById<android.widget.TextView>(R.id.text_test_result).setText(R.string.test_ok)
    }

    @Test
    fun einstellungen() {
        ActivityScenario.launch(SettingsActivity::class.java).use { scenario ->
            idle()
            onView(isRoot()).captureRoboImage("$dir/04_einstellungen_mqtt.png")
            scrollTo(scenario, AppSettings.K_TRANSITION)
            onView(isRoot()).captureRoboImage("$dir/05_einstellungen_bild.png")
            scrollTo(scenario, AppSettings.K_BLACK_BRIGHTNESS)
            onView(isRoot()).captureRoboImage("$dir/06_einstellungen_ersatzfarben.png")
            scrollTo(scenario, AppSettings.K_APPS_OFF)
            onView(isRoot()).captureRoboImage("$dir/07_einstellungen_app_profile.png")
            scrollTo(scenario, AppSettings.K_APP_TOPIC)
            onView(isRoot()).captureRoboImage("$dir/08_einstellungen_verhalten_ha.png")
        }
    }

    @Test
    fun dialog_ersatzmodus() = dialog(AppSettings.K_BLACK_MODE, "10_dialog_ersatzmodus.png")

    @Test
    fun dialog_farbauswahl() = dialog(AppSettings.K_BLACK_COLOR_LEFT, "11_dialog_farbauswahl.png")

    @Test
    fun dialog_app_profil() = dialog(AppSettings.K_APPS_FALLBACK, "12_dialog_app_profil.png")

    private fun main(file: String, focusId: Int? = null, prepare: (MainActivity) -> Unit = {}) {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            idle()
            scenario.onActivity { activity ->
                prepare(activity)
                // Fokusrahmen wie mit der Fernbedienung (Robolectric läuft im Touch-Modus)
                focusId?.let { id ->
                    activity.findViewById<android.view.View>(id).apply {
                        isFocusableInTouchMode = true
                        requestFocus()
                    }
                }
            }
            idle()
            onView(isRoot()).captureRoboImage("$dir/$file")
        }
    }

    private fun dialog(key: String, file: String) {
        ActivityScenario.launch(SettingsActivity::class.java).use { scenario ->
            idle()
            scrollTo(scenario, key)
            scenario.onActivity { activity ->
                fragment(activity).findPreference<Preference>(key)!!.performClick()
            }
            idle()
            captureScreenRoboImage("$dir/$file")
        }
    }

    private fun fragment(activity: SettingsActivity) =
        activity.supportFragmentManager.findFragmentById(R.id.settings_container) as PreferenceFragmentCompat

    private fun scrollTo(scenario: ActivityScenario<SettingsActivity>, key: String) {
        scenario.onActivity { fragment(it).scrollToPreference(key) }
        idle()
    }

    private fun idle() {
        repeat(3) { ShadowLooper.idleMainLooper() }
    }
}
