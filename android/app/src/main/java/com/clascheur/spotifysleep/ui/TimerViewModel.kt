package com.clascheur.spotifysleep.ui

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.ViewModel
import com.clascheur.spotifysleep.data.SongInfo
import com.clascheur.spotifysleep.data.TimerState
import com.clascheur.spotifysleep.service.MediaListenerService
import com.clascheur.spotifysleep.service.SleepTimerService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class TimerViewModel : ViewModel() {

    val timerState: StateFlow<TimerState> = SleepTimerService.timerStateFlow
    val currentSong: StateFlow<SongInfo?> = MediaListenerService.currentSongFlow

    private val _selectedMinutes = MutableStateFlow(15)
    val selectedMinutes: StateFlow<Int> = _selectedMinutes.asStateFlow()

    private val _finishCurrentSong = MutableStateFlow(true)
    val finishCurrentSong: StateFlow<Boolean> = _finishCurrentSong.asStateFlow()

    private val _fadeOut = MutableStateFlow(true)
    val fadeOut: StateFlow<Boolean> = _fadeOut.asStateFlow()

    private val _hasNotificationAccess = MutableStateFlow(false)
    val hasNotificationAccess: StateFlow<Boolean> = _hasNotificationAccess.asStateFlow()

    fun checkPermission(context: Context) {
        val enabledPackages = NotificationManagerCompat.getEnabledListenerPackages(context)
        _hasNotificationAccess.value = enabledPackages.contains(context.packageName)
        if (_hasNotificationAccess.value) {
            MediaListenerService.refreshSessions(context)
        }
    }

    fun openNotificationAccessSettings(context: Context) {
        val intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        context.startActivity(intent)
    }

    fun setSelectedMinutes(minutes: Int) {
        _selectedMinutes.value = minutes.coerceIn(1, 180)
    }

    fun setFinishCurrentSong(finish: Boolean) {
        _finishCurrentSong.value = finish
    }

    fun setFadeOut(fade: Boolean) {
        _fadeOut.value = fade
    }

    fun startTimer(context: Context) {
        SleepTimerService.start(
            context = context,
            totalMinutes = _selectedMinutes.value,
            finishSong = _finishCurrentSong.value,
            fadeOut = _fadeOut.value
        )
    }

    fun extendTimer(context: Context, extraMinutes: Int) {
        SleepTimerService.extend(context, extraMinutes)
    }

    fun pauseNow(context: Context) {
        SleepTimerService.pauseNow(context)
    }

    fun cancelTimer(context: Context) {
        SleepTimerService.cancel(context)
    }
}
