package io.github.hxcde.ambilight.ui

import android.app.Activity
import android.content.ActivityNotFoundException
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.ComponentActivity
import io.github.hxcde.ambilight.AppSettings
import io.github.hxcde.ambilight.R
import io.github.hxcde.ambilight.service.AmbilightService

/**
 * Unsichtbare Activity, die nur die Bildschirmfreigabe anfragt und den Dienst startet.
 * Wird von der Startseite, nach dem Booten und nach dem Aufwachen aus dem Standby genutzt.
 */
class CaptureRequestActivity : ComponentActivity() {

    private val request = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val data = result.data
        if (result.resultCode == Activity.RESULT_OK && data != null) {
            AmbilightService.start(this, result.resultCode, data)
        } else {
            Toast.makeText(this, R.string.capture_denied, Toast.LENGTH_LONG).show()
        }
        finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState != null) return // Ergebnis kommt nach Neuaufbau ohnehin an

        if (!AppSettings(this).isConfigured) {
            Toast.makeText(this, R.string.not_configured, Toast.LENGTH_LONG).show()
            finish()
            return
        }
        val mpm = getSystemService(MediaProjectionManager::class.java)
        val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            mpm.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay())
        } else {
            mpm.createScreenCaptureIntent()
        }
        try {
            request.launch(intent)
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, R.string.capture_unavailable, Toast.LENGTH_LONG).show()
            finish()
        }
    }
}
