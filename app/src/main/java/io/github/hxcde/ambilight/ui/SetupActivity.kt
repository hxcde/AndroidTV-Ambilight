package io.github.hxcde.ambilight.ui

import android.content.ActivityNotFoundException
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import io.github.hxcde.ambilight.R
import io.github.hxcde.ambilight.service.AmbilightState

/** Einrichtung: zeigt, welche optionalen Freigaben fehlen, wofür sie gut sind und wie man sie erteilt. */
class SetupActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_setup)
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    private fun render() {
        val items = Permissions.all(this)
        val status = AmbilightState.status.value
        val container = findViewById<LinearLayout>(R.id.setup_items)
        container.removeAllViews()
        val inflater = LayoutInflater.from(this)
        var firstButton: Button? = null
        for (item in items) {
            val v = inflater.inflate(R.layout.item_permission, container, false)
            v.findViewById<TextView>(R.id.perm_title).setText(item.title)
            val viaHa = !item.granted && Permissions.coveredByHa(item, status)
            val done = item.granted || viaHa
            val hint = Permissions.haHint(item)
            v.findViewById<TextView>(R.id.perm_desc).text = if (!done && hint != null) {
                getString(item.description) + "\n" + getString(hint)
            } else {
                getString(item.description)
            }
            v.findViewById<TextView>(R.id.perm_status).apply {
                setText(if (item.granted) R.string.perm_granted else if (viaHa) R.string.perm_via_ha else R.string.perm_missing)
                setTextColor(getColor(if (done) R.color.ok else R.color.warn))
            }
            v.findViewById<TextView>(R.id.perm_adb).apply {
                text = getString(R.string.perm_adb, item.adb)
                visibility = if (done) View.GONE else View.VISIBLE
            }
            v.findViewById<Button>(R.id.perm_open).apply {
                val intent = item.settingsIntent
                visibility = if (!done && intent != null) View.VISIBLE else View.GONE
                setOnClickListener {
                    try {
                        startActivity(intent)
                    } catch (_: ActivityNotFoundException) {
                        Toast.makeText(this@SetupActivity, R.string.perm_settings_missing, Toast.LENGTH_LONG).show()
                    }
                }
                if (isVisible && firstButton == null) firstButton = this
            }
            container.addView(v)
        }
        val missing = items.count { !it.granted && !Permissions.coveredByHa(it, status) }
        findViewById<TextView>(R.id.setup_summary).text = if (missing == 0) {
            getString(R.string.setup_all_granted)
        } else {
            resources.getQuantityString(R.plurals.setup_missing, missing, missing)
        }
        findViewById<View>(R.id.setup_all_box).visibility = if (missing == 0) View.GONE else View.VISIBLE
        findViewById<TextView>(R.id.setup_all_command).text = Permissions.combinedAdb(this)
        (firstButton ?: findViewById<Button>(R.id.setup_back)).requestFocus()
        findViewById<Button>(R.id.setup_back).setOnClickListener { finish() }
    }
}
