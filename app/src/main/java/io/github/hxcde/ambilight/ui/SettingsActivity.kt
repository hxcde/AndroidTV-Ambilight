package io.github.hxcde.ambilight.ui

import android.os.Bundle
import android.text.InputType
import androidx.appcompat.app.AppCompatActivity
import android.widget.Toast
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import io.github.hxcde.ambilight.AppSettings
import io.github.hxcde.ambilight.BlackMode
import io.github.hxcde.ambilight.R
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
            list.setOnPreferenceChangeListener { _, newValue ->
                custom.isVisible = colorMode && newValue == FixedColor.CUSTOM
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
                if (!ok) Toast.makeText(requireContext(), R.string.invalid_hex, Toast.LENGTH_LONG).show()
                ok
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
