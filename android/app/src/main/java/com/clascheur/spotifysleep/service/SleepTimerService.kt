package com.clascheur.spotifysleep.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import com.clascheur.spotifysleep.R
import com.clascheur.spotifysleep.data.SongInfo
import com.clascheur.spotifysleep.data.TimerPhase
import com.clascheur.spotifysleep.data.TimerState
import com.clascheur.spotifysleep.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class SleepTimerService : Service() {

    private val serviceScope = CoroutineScope(Dispatchers.Default + Job())
    private var timerJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var notificationManager: NotificationManager? = null

    override fun onCreate() {
        super.onCreate()
        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        createNotificationChannel()

        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "SpotifySleepTimer::WakeLock"
        ).apply {
            setReferenceCounted(false)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val totalSeconds = intent.getLongExtra(EXTRA_SECONDS, 900L)
                val finishSong = intent.getBooleanExtra(EXTRA_FINISH_SONG, true)
                val fadeOut = intent.getBooleanExtra(EXTRA_FADE_OUT, true)
                startTimer(totalSeconds, finishSong, fadeOut)
            }
            ACTION_EXTEND_5 -> {
                extendTimer(300L)
            }
            ACTION_EXTEND_15 -> {
                extendTimer(900L)
            }
            ACTION_PAUSE_NOW -> {
                pauseAndStop()
            }
            ACTION_CANCEL -> {
                cancelTimer()
            }
        }
        return START_NOT_STICKY
    }

    private fun startTimer(totalSeconds: Long, finishSong: Boolean, fadeOut: Boolean) {
        timerJob?.cancel()
        wakeLock?.acquire(3 * 60 * 60 * 1000L) // up to 3 hours safety timeout

        _timerStateFlow.value = TimerState(
            phase = TimerPhase.COUNTDOWN,
            remainingSeconds = totalSeconds,
            totalSeconds = totalSeconds,
            finishCurrentSong = finishSong,
            fadeOut = fadeOut
        )

        startForeground(NOTIFICATION_ID, buildNotification("Timer started: ${formatTime(totalSeconds)}"))

        timerJob = serviceScope.launch {
            var secondsLeft = totalSeconds

            // 1. Primary Countdown
            while (isActive && secondsLeft > 0) {
                _timerStateFlow.value = _timerStateFlow.value.copy(
                    phase = TimerPhase.COUNTDOWN,
                    remainingSeconds = secondsLeft
                )
                notificationManager?.notify(
                    NOTIFICATION_ID,
                    buildNotification("Sleeping in ${formatTime(secondsLeft)}")
                )
                delay(1000L)
                secondsLeft--
            }

            if (!isActive) return@launch

            // 2. Window expired: Check if we should finish current song
            if (finishSong) {
                waitForSongToEnd(fadeOut)
            } else {
                if (fadeOut) {
                    performFadeOut()
                }
                finishPlayback()
            }
        }
    }

    private suspend fun waitForSongToEnd(fadeOut: Boolean) {
        _timerStateFlow.value = _timerStateFlow.value.copy(
            phase = TimerPhase.WAITING_FOR_TRACK_END,
            remainingSeconds = 0L
        )

        // Refresh active media session
        MediaListenerService.refreshSessions(this)
        delay(400L)

        val currentSong: SongInfo? = MediaListenerService.currentSongFlow.value

        if (currentSong == null || !currentSong.isPlaying) {
            Log.d(TAG, "No song currently playing when timer expired, finishing immediately")
            finishPlayback()
            return
        }

        val targetTitle = currentSong.title
        val targetArtist = currentSong.artist

        _timerStateFlow.value = _timerStateFlow.value.copy(
            targetTrackTitle = targetTitle,
            targetTrackArtist = targetArtist
        )

        notificationManager?.notify(
            NOTIFICATION_ID,
            buildNotification("Finishing: '$targetTitle' — letting song end...")
        )

        var notPlayingCount = 0

        while (serviceScope.isActive) {
            val song = MediaListenerService.currentSongFlow.value

            // Debounce momentary playback interruptions during track change
            if (song == null || !song.isPlaying) {
                notPlayingCount++
                if (notPlayingCount >= 8) {
                    Log.d(TAG, "Playback stopped or disconnected for >3s, finishing playback")
                    break
                }
            } else {
                notPlayingCount = 0
            }

            // CRITICAL CHECK 1: If track title changed, the target song completed!
            if (song != null && song.title != targetTitle && targetTitle.isNotBlank()) {
                Log.d(TAG, "Track changed from '$targetTitle' to '${song.title}'! Pausing immediately!")
                break
            }

            val remMs = song?.remainingMs ?: 0L
            val remSec = (remMs / 1000L).coerceAtLeast(0L)

            _timerStateFlow.value = _timerStateFlow.value.copy(
                trackRemainingSeconds = remSec
            )

            notificationManager?.notify(
                NOTIFICATION_ID,
                buildNotification("Finishing '$targetTitle' (${remSec}s left)")
            )

            // Gentle fade-out near song end
            if (fadeOut && song != null && song.isPlaying && song.durationMs > 0L &&
                remMs <= VolumeFade.DURATION_MS) {
                val ratio = VolumeFade.ratioForRemaining(remMs)
                MediaListenerService.setVolumeRatio(this, ratio)
            }

            // CRITICAL CHECK 2: Close enough to natural end (< 800ms)
            if (song != null && song.durationMs > 0L && remMs in 0..800L) {
                Log.d(TAG, "Track reached natural end ($remMs ms left). Pausing now!")
                break
            }

            delay(400L)
        }

        finishPlayback()
    }

    private suspend fun performFadeOut() {
        val startedAt = SystemClock.elapsedRealtime()
        while (serviceScope.isActive) {
            val remainingMs = (VolumeFade.DURATION_MS -
                (SystemClock.elapsedRealtime() - startedAt)).coerceAtLeast(0L)
            MediaListenerService.setVolumeRatio(this, VolumeFade.ratioForRemaining(remainingMs))
            if (remainingMs == 0L) break
            delay(minOf(VolumeFade.UPDATE_INTERVAL_MS, remainingMs))
        }
    }

    private fun finishPlayback() {
        Log.d(TAG, "Executing finishPlayback with multi-pulse pause sequence")

        // 1. Request permanent audio focus
        val focusRequest = MediaListenerService.requestPermanentAudioFocus(this)

        // 2. Multi-pulse pause sequence over 2 seconds to catch any track transitions
        serviceScope.launch {
            val delays = listOf(0L, 200L, 400L, 700L, 1100L, 1600L)
            for (d in delays) {
                if (d > 0) delay(d)
                MediaListenerService.pausePlayback(this@SleepTimerService)
                Log.d(TAG, "Pause pulse executed at delay: $d ms")
            }

            // Restore volume safely after Spotify has fully stopped
            delay(3000L)
            MediaListenerService.restoreVolume(this@SleepTimerService)

            // Release focus request after playback has been terminated
            if (focusRequest != null) {
                val audioManager = getSystemService(Context.AUDIO_SERVICE) as? AudioManager
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    audioManager?.abandonAudioFocusRequest(focusRequest)
                } else {
                    @Suppress("DEPRECATION")
                    audioManager?.abandonAudioFocus(null)
                }
            }
        }

        _timerStateFlow.value = TimerState(phase = TimerPhase.COMPLETED)
        showCompletedNotification()
        stopService()
    }

    private fun extendTimer(extraSeconds: Long) {
        val current = _timerStateFlow.value
        val newRemaining = current.remainingSeconds + extraSeconds
        val newTotal = current.totalSeconds + extraSeconds

        _timerStateFlow.value = current.copy(
            phase = TimerPhase.COUNTDOWN,
            remainingSeconds = newRemaining,
            totalSeconds = newTotal
        )

        startTimer(newRemaining, current.finishCurrentSong, current.fadeOut)
    }

    private fun pauseAndStop() {
        timerJob?.cancel()
        finishPlayback()
    }

    private fun cancelTimer() {
        timerJob?.cancel()
        MediaListenerService.restoreVolume(this)
        _timerStateFlow.value = TimerState(phase = TimerPhase.IDLE)
        stopService()
    }

    private fun stopService() {
        if (wakeLock?.isHeld == true) {
            try {
                wakeLock?.release()
            } catch (e: Exception) {
                Log.w(TAG, "Error releasing wakelock: ${e.message}")
            }
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.notification_channel_desc)
                setShowBadge(false)
            }
            notificationManager?.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(contentText: String): Notification {
        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val openAppPendingIntent = PendingIntent.getActivity(
            this, 0, openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val extendIntent = Intent(this, SleepTimerService::class.java).apply {
            action = ACTION_EXTEND_5
        }
        val extendPendingIntent = PendingIntent.getService(
            this, 1, extendIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val pauseIntent = Intent(this, SleepTimerService::class.java).apply {
            action = ACTION_PAUSE_NOW
        }
        val pausePendingIntent = PendingIntent.getService(
            this, 2, pauseIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val cancelIntent = Intent(this, SleepTimerService::class.java).apply {
            action = ACTION_CANCEL
        }
        val cancelPendingIntent = PendingIntent.getService(
            this, 3, cancelIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_timer)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(contentText)
            .setContentIntent(openAppPendingIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(0, "+5m", extendPendingIntent)
            .addAction(0, "Pause Now", pausePendingIntent)
            .addAction(0, "Cancel", cancelPendingIntent)
            .build()
    }

    private fun showCompletedNotification() {
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_timer)
            .setContentTitle("Spotify Sleep Timer")
            .setContentText("Playback paused after current track. Good night! 🌙")
            .setAutoCancel(true)
            .build()
        notificationManager?.notify(NOTIFICATION_ID + 1, notification)
    }

    private fun formatTime(seconds: Long): String {
        val mins = seconds / 60
        val secs = seconds % 60
        return String.format("%d:%02d", mins, secs)
    }

    override fun onDestroy() {
        super.onDestroy()
        timerJob?.cancel()
        if (wakeLock?.isHeld == true) {
            try {
                wakeLock?.release()
            } catch (e: Exception) {
                Log.w(TAG, "Error releasing wakelock in onDestroy: ${e.message}")
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val TAG = "SleepTimerService"
        const val CHANNEL_ID = "spotify_sleep_timer_channel"
        const val NOTIFICATION_ID = 1001

        const val ACTION_START = "com.clascheur.spotifysleep.ACTION_START"
        const val ACTION_EXTEND_5 = "com.clascheur.spotifysleep.ACTION_EXTEND_5"
        const val ACTION_EXTEND_15 = "com.clascheur.spotifysleep.ACTION_EXTEND_15"
        const val ACTION_PAUSE_NOW = "com.clascheur.spotifysleep.ACTION_PAUSE_NOW"
        const val ACTION_CANCEL = "com.clascheur.spotifysleep.ACTION_CANCEL"

        const val EXTRA_SECONDS = "extra_seconds"
        const val EXTRA_FINISH_SONG = "extra_finish_song"
        const val EXTRA_FADE_OUT = "extra_fade_out"

        private val _timerStateFlow = MutableStateFlow(TimerState())
        val timerStateFlow: StateFlow<TimerState> = _timerStateFlow.asStateFlow()

        fun start(context: Context, totalMinutes: Int, finishSong: Boolean, fadeOut: Boolean) {
            val intent = Intent(context, SleepTimerService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_SECONDS, totalMinutes * 60L)
                putExtra(EXTRA_FINISH_SONG, finishSong)
                putExtra(EXTRA_FADE_OUT, fadeOut)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun extend(context: Context, extraMinutes: Int) {
            val intent = Intent(context, SleepTimerService::class.java).apply {
                action = if (extraMinutes == 15) ACTION_EXTEND_15 else ACTION_EXTEND_5
            }
            context.startService(intent)
        }

        fun pauseNow(context: Context) {
            val intent = Intent(context, SleepTimerService::class.java).apply {
                action = ACTION_PAUSE_NOW
            }
            context.startService(intent)
        }

        fun cancel(context: Context) {
            val intent = Intent(context, SleepTimerService::class.java).apply {
                action = ACTION_CANCEL
            }
            context.startService(intent)
        }
    }
}
