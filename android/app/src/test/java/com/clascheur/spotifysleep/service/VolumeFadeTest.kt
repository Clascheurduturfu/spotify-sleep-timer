package com.clascheur.spotifysleep.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VolumeFadeTest {
    @Test
    fun fadeStartsAtListeningLevelAndOnlyLowersItOverTwentySeconds() {
        assertEquals(20_000L, VolumeFade.DURATION_MS)
        // Include mute, quiet listening, and devices with many volume steps.
        for (initialVolume in 0..100) {
            val fade = VolumeFade(initialVolume)
            var volume = initialVolume
            assertEquals(initialVolume, fade.targetVolume(1f, volume))
            for (elapsed in 0L..20_000L step 200L) {
                val next = fade.targetVolume(
                    VolumeFade.ratioForRemaining(20_000L - elapsed), volume
                )
                assertTrue("Fade raised volume from $volume to $next", next <= volume)
                volume = next
            }
            assertEquals(0, volume)
        }
    }

    @Test
    fun seekingBackwardsCannotRaiseVolume() {
        val fade = VolumeFade(10)
        assertEquals(5, fade.targetVolume(0.5f, 10))
        assertEquals(5, fade.targetVolume(1f, 5))
        assertEquals(2, fade.targetVolume(0.2f, 5))
    }

    @Test
    fun manualVolumeReductionIsRespected() {
        val fade = VolumeFade(10)
        assertEquals(8, fade.targetVolume(0.8f, 10))
        assertEquals(2, fade.targetVolume(0.7f, 2))
        assertEquals(2, fade.targetVolume(0.6f, 2))
        assertEquals(0, fade.targetVolume(0f, 2))
        assertEquals(10, fade.originalVolume)
    }

    @Test
    fun lateStartAndTimeOutsideFadeWindowStayWithinBounds() {
        val fade = VolumeFade(4)
        assertEquals(2, fade.targetVolume(VolumeFade.ratioForRemaining(10_000L), 4))
        assertEquals(1f, VolumeFade.ratioForRemaining(30_000L), 0f)
        assertEquals(0f, VolumeFade.ratioForRemaining(-100L), 0f)
        assertEquals(0, fade.targetVolume(VolumeFade.ratioForRemaining(-100L), 2))
    }
}
