package io.github.hxcde.ambilight.ui

import android.content.Intent
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.hxcde.ambilight.engine.Mode
import io.github.hxcde.ambilight.service.AmbilightService
import io.github.hxcde.ambilight.service.AmbilightState
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class RemoteControlActivityTest {
    @After
    fun tearDown() = AmbilightState.reset()

    private fun launch(uri: String): RemoteControlActivity {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(uri))
            .setClass(ApplicationProvider.getApplicationContext(), RemoteControlActivity::class.java)
        return Robolectric.buildActivity(RemoteControlActivity::class.java, intent).create().get()
    }

    @Test
    fun startOpensCaptureRequest() {
        val a = launch("tvambilight://start")
        assertEquals(CaptureRequestActivity::class.java.name, shadowOf(a).nextStartedActivity.component?.className)
    }

    @Test
    fun startDoesNothingWhenRunning() {
        AmbilightState.update { it.copy(mode = Mode.ACTIVE) }
        assertNull(shadowOf(launch("tvambilight://start")).nextStartedActivity)
    }

    @Test
    fun stopStopsService() {
        val a = launch("tvambilight://stop")
        val stopped = shadowOf(ApplicationProvider.getApplicationContext<android.app.Application>()).nextStoppedService
        assertEquals(AmbilightService::class.java.name, stopped?.component?.className)
        assertEquals(true, a.isFinishing)
    }
}
