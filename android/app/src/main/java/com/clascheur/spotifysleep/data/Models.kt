package com.clascheur.spotifysleep.data

enum class TimerPhase {
    IDLE,
    COUNTDOWN,
    WAITING_FOR_TRACK_END,
    COMPLETED
}

data class SongInfo(
    val title: String,
    val artist: String,
    val album: String = "",
    val durationMs: Long = 0L,
    val positionMs: Long = 0L,
    val lastPositionUpdate: Long = 0L,
    val isPlaying: Boolean = false,
    val packageName: String = ""
) {
    val estimatedPositionMs: Long
        get() {
            if (!isPlaying || lastPositionUpdate == 0L) return positionMs
            val elapsed = android.os.SystemClock.elapsedRealtime() - lastPositionUpdate
            return (positionMs + elapsed).coerceAtMost(durationMs)
        }

    val remainingMs: Long
        get() {
            if (durationMs <= 0L) return 0L
            return (durationMs - estimatedPositionMs).coerceAtLeast(0L)
        }
}

data class TimerState(
    val phase: TimerPhase = TimerPhase.IDLE,
    val remainingSeconds: Long = 0L,
    val totalSeconds: Long = 0L,
    val targetTrackTitle: String? = null,
    val targetTrackArtist: String? = null,
    val trackRemainingSeconds: Long = 0L,
    val finishCurrentSong: Boolean = true,
    val fadeOut: Boolean = true
)
