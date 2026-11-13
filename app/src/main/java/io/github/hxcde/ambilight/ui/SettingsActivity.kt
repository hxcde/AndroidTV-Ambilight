package io.github.hxcde.ambilight.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.drawable.GradientDrawable
import android.provider.Settings
import android.os.Bundle
import android.text.InputType
import androidx.appcompat.app.AppCompatActivity
import android.widget.Toast
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.MultiSelectListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import io.github.hxcde.ambilight.AppSettings
import io.github.hxcde.ambilight.BlackMode
import io.github.hxcde.ambilight.Option
import io.github.hxcde.ambilight.Options
import io.github.hxcde.ambilight.R
import io.github.hxcde.ambilight.engine.AppProfiles
import io.github.hxcde.ambilight.service.ForegroundAppDetector
import io.github.hxcde.ambilight.service.MediaWatcher
import io.github.hxcde.ambilight.light.FixedColor

class SettingsActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.settings_container, SettingsFragment())
                .commit()
        }
    }

    class SettingsFragment : PreferenceFragmentCompat() {
        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            setPreferencesFromResource(R.xml.preferences, rootKey)

            text(AppSettings.K_HOST, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI)
            text(AppSettings.K_PORT, InputType.TYPE_CLASS_NUMBER)
            text(AppSettings.K_USER, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS)
            text(AppSettings.K_BASE_TOPIC, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS)
            text(AppSettings.K_LIGHT_LEFT, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS)
            text(AppSettings.K_LIGHT_RIGHT, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS)
            text(AppSettings.K_HA_PREFIX, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS)
            text(AppSettings.K_APP_TOPIC, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS)

            options(AppSettings.K_BLACK_MODE, Options.BLACK_MODE)
            options(AppSettings.K_BLACK_COLOR_LEFT, Options.FIXED_COLOR)
            options(AppSettings.K_BLACK_COLOR_RIGHT, Options.FIXED_COLOR)
            options(AppSettings.K_BRIGHTNESS_MODE, Options.BRIGHTNESS_MODE)
            options(AppSettings.K_STANDBY_ACTION, Options.LIGHT_ACTION)
            options(AppSettings.K_STOP_ACTION, Options.LIGHT_ACTION)
            options(AppSettings.K_WAKE_ACTION, Options.WAKE_ACTION)
            options(AppSettings.K_PAUSE_ACTION, Options.PAUSE_ACTION)
            sceneColor(AppSettings.K_STANDARD_COLOR_LEFT)
            sceneColor(AppSettings.K_STANDARD_COLOR_RIGHT)
            sceneColor(AppSettings.K_PAUSE_COLOR)
            findPreference<Preference>(AppSettings.K_MEDIA_PERMISSION)?.setOnPreferenceClickListener {
                openSettings(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS, R.string.media_permission_settings_missing)
                true
            }
            appLists()
            findPreference<Preference>(AppSettings.K_APP_PERMISSION)?.setOnPreferenceClickListener {
                openSettings(Settings.ACTION_USAGE_ACCESS_SETTINGS, R.string.app_permission_settings_missing)
                true
            }

            fixedColor(AppSettings.K_BLACK_COLOR_LEFT, AppSettings.K_BLACK_COLOR_LEFT_CUSTOM)
            fixedColor(AppSettings.K_BLACK_COLOR_RIGHT, AppSettings.K_BLACK_COLOR_RIGHT_CUSTOM)
            findPreference<ListPreference>(AppSettings.K_BLACK_MODE)?.let { mode ->
                refreshBlackSection(mode.value)
                mode.setOnPreferenceChangeListener { _, newValue ->
                    refreshBlackSection(newValue as String)
                    true
                }
            }

            findPreference<EditTextPreference>(AppSettings.K_PASSWORD)?.apply {
                setOnBindEditTextListener {
                    it.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                    it.setSelection(it.text.length)
                }
                setSummaryProvider { pref ->
                    if ((pref as EditTextPreference).text.isNullOrEmpty()) getString(R.string.not_set) else "••••••••"
                }
            }
        }

        private var colorMode = true

        override fun onResume() {
            super.onResume()
            findPreference<Preference>(AppSettings.K_APP_PERMISSION)?.setSummary(
                if (ForegroundAppDetector.hasPermission(requireContext())) R.string.app_permission_ok else R.string.app_permission_missing,
            )
            findPreference<Preference>(AppSettings.K_MEDIA_PERMISSION)?.setSummary(
                if (MediaWatcher.hasPermission(requireContext())) R.string.media_permission_ok else R.string.media_permission_missing,
            )
        }

        private fun openSettings(action: String, missingText: Int) {
            try {
                startActivity(Intent(action))
            } catch (_: ActivityNotFoundException) {
                Toast.makeText(requireContext(), missingText, Toast.LENGTH_LONG).show()
            }
        }

        /** Farbauswahl für Standard-/Pausenlicht mit Farbfeld als Icon. */
        private fun sceneColor(key: String) {
            val list = findPreference<ListPreference>(key) ?: return
            list.entries = Options.SCENE_COLOR.map { it.label }.toTypedArray()
            list.entryValues = Options.SCENE_COLOR.map { it.value }.toTypedArray()
            val update = { v: String? -> list.icon = swatch((FixedColor.parse(v) ?: FixedColor.DEFAULT).previewArgb) }
            // Aus Home Assistant gesetzte Farben stehen als #RRGGBB in der Auswahl
            list.setSummaryProvider { p ->
                val v = (p as ListPreference).value
                Options.labelOf(Options.SCENE_COLOR, v) ?: v?.uppercase() ?: getString(R.string.not_set)
            }
            update(list.value)
            list.setOnPreferenceChangeListener { _, newValue ->
                update(newValue as String)
                true
            }
        }

        private fun options(key: String, options: List<Option>) {
            findPreference<ListPreference>(key)?.apply {
                entries = options.map { it.label }.toTypedArray()
                entryValues = options.map { it.value }.toTypedArray()
            }
        }

        /** Auswahl der installierten Apps für die drei App-Profile. */
        private fun appLists() {
            val ctx = requireContext()
            val pm = ctx.packageManager
            val apps = LinkedHashMap<String, String>()
            for (category in listOf(Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER)) {
                val intent = Intent(Intent.ACTION_MAIN).addCategory(category)
                for (ri in pm.queryIntentActivities(intent, 0)) {
                    val pkg = ri.activityInfo?.packageName ?: continue
                    if (pkg != ctx.packageName) apps.putIfAbsent(pkg, ri.loadLabel(pm).toString())
                }
            }
            val sorted = apps.entries.sortedBy { it.value.lowercase() }
            val prefs = preferenceManager.sharedPreferences
            for (key in listOf(AppSettings.K_APPS_FALLBACK, AppSettings.K_APPS_STANDARD, AppSettings.K_APPS_OFF)) {
                val pref = findPreference<MultiSelectListPreference>(key) ?: continue
                pref.entries = sorted.map { it.value }.toTypedArray()
                pref.entryValues = sorted.map { it.key }.toTypedArray()
                if (key == AppSettings.K_APPS_FALLBACK && prefs?.contains(key) == false) pref.values = AppProfiles.DEFAULT_FALLBACK
                pref.setSummaryProvider { p ->
                    val selected = (p as MultiSelectListPreference).values.mapNotNull { apps[it] }.sortedBy { it.lowercase() }
                    when {
                        apps.isEmpty() -> getString(R.string.apps_not_found)
                        selected.isEmpty() -> getString(R.string.apps_none)
                        else -> selected.joinToString(", ")
                    }
                }
            }
        }

        /** Farb- und Helligkeitswahl nur zeigen, wenn der Ersatzmodus „Feste Farbe“ aktiv ist. */
        private fun refreshBlackSection(mode: String?) {
            colorMode = mode == null || mode == BlackMode.COLOR.name
            findPreference<Preference>(AppSettings.K_BLACK_BRIGHTNESS)?.isVisible = colorMode
            for ((listKey, customKey) in COLOR_KEYS) {
                val list = findPreference<ListPreference>(listKey) ?: continue
                list.isVisible = colorMode
                findPreference<Preference>(customKey)?.isVisible = colorMode && list.value == FixedColor.CUSTOM
            }
        }

        /** Farbauswahl je Lampe; das Hex-Feld ist nur bei „Eigene Farbe“ sichtbar und wird geprüft. */
        private fun fixedColor(listKey: String, customKey: String) {
            val list = findPreference<ListPreference>(listKey) ?: return
            val custom = findPreference<EditTextPreference>(customKey) ?: return
            fun updateSwatch(choice: String?, hex: String?) {
                val value = if (choice == FixedColor.CUSTOM) hex else choice
                list.icon = swatch((FixedColor.parse(value) ?: FixedColor.DEFAULT).previewArgb)
            }
            updateSwatch(list.value, custom.text)
            list.setOnPreferenceChangeListener { _, newValue ->
                custom.isVisible = colorMode && newValue == FixedColor.CUSTOM
                updateSwatch(newValue as String, custom.text)
                true
            }
            custom.setOnBindEditTextListener {
                it.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS or
                    InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
                it.hint = "#8000FF"
                it.setSelection(it.text.length)
            }
            custom.setSummaryProvider { pref ->
                (pref as EditTextPreference).text?.takeIf { it.isNotBlank() }?.uppercase() ?: getString(R.string.not_set)
            }
            custom.setOnPreferenceChangeListener { _, newValue ->
                val ok = FixedColor.isValidHex(newValue as String)
                if (ok) updateSwatch(list.value, newValue) else {
                    Toast.makeText(requireContext(), R.string.invalid_hex, Toast.LENGTH_LONG).show()
                }
                ok
            }
        }

        /** Kleines Farbfeld als Icon der Farbauswahl. */
        private fun swatch(argb: Int): GradientDrawable {
            val d = resources.displayMetrics.density
            return GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 6 * d
                setColor(argb)
                setStroke((1 * d).toInt().coerceAtLeast(1), 0x40FFFFFF)
                setSize((28 * d).toInt(), (28 * d).toInt())
            }
        }

        private fun text(key: String, inputType: Int) {
            findPreference<EditTextPreference>(key)?.apply {
                setOnBindEditTextListener {
                    it.inputType = inputType
                    it.setSelection(it.text.length)
                }
                setSummaryProvider { pref ->
                    (pref as EditTextPreference).text?.takeIf { it.isNotBlank() } ?: getString(R.string.not_set)
                }
            }
        }

        private companion object {
            val COLOR_KEYS = listOf(
                AppSettings.K_BLACK_COLOR_LEFT to AppSettings.K_BLACK_COLOR_LEFT_CUSTOM,
                AppSettings.K_BLACK_COLOR_RIGHT to AppSettings.K_BLACK_COLOR_RIGHT_CUSTOM,
            )
        }
    }
}
