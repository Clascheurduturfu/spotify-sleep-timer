package com.clascheur.spotifysleep.service

/** A fade is relative to the listening level and must never turn the volume up. */
internal class VolumeFade(val originalVolume: Int) {
    private var lastVolume = originalVolume

    fun targetVolume(ratio: Float, currentVolume: Int): Int {
        val target = (originalVolume * ratio.coerceIn(0f, 1f)).toInt()
        // Also respect manual volume reductions and a seek backwards during the fade.
        lastVolume = minOf(target, lastVolume, currentVolume).coerceAtLeast(0)
        return lastVolume
    }

    companion object {
        const val DURATION_MS = 20_000L
        const val UPDATE_INTERVAL_MS = 200L

        fun ratioForRemaining(remainingMs: Long): Float =
            (remainingMs.toFloat() / DURATION_MS).coerceIn(0f, 1f)
    }
}
