package io.github.hxcde.ambilight.mqtt

import io.github.hxcde.ambilight.engine.Playback
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ExternalStateTest {
    @Test
    fun appPackageNames() {
        assertEquals("com.netflix.ninja", ExternalState.parseApp(" com.netflix.ninja "))
        assertNull(ExternalState.parseApp(""))
        assertNull(ExternalState.parseApp("unavailable"))
        assertNull(ExternalState.parseApp("None"))
        assertNull(ExternalState.parseApp("com.evil; rm -rf"))
    }

    @Test
    fun mediaPlayerStates() {
        assertEquals(Playback.PLAYING, ExternalState.parsePlayback("playing"))
        assertEquals(Playback.PLAYING, ExternalState.parsePlayback("buffering"))
        assertEquals(Playback.PAUSED, ExternalState.parsePlayback("paused"))
        assertEquals(Playback.NONE, ExternalState.parsePlayback("idle"))
        assertEquals(Playback.NONE, ExternalState.parsePlayback("off"))
        assertEquals(Playback.NONE, ExternalState.parsePlayback("unavailable"))
    }
}
