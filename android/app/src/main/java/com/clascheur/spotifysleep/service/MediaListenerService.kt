package com.clascheur.spotifysleep.service

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Build
import android.os.SystemClock
import android.service.notification.NotificationListenerService
import android.util.Log
import android.view.KeyEvent
import com.clascheur.spotifysleep.data.SongInfo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class MediaListenerService : NotificationListenerService() {

    private var mediaSessionManager: MediaSessionManager? = null
    private var activeController: MediaController? = null
    private var controllerCallback: MediaController.Callback? = null

    private val sessionsChangedListener =
        MediaSessionManager.OnActiveSessionsChangedListener { controllers ->
            updateActiveController(controllers)
        }

    override fun onCreate() {
        super.onCreate()
        instance = this
        isServiceConnected.value = true
        initMediaSessionManager()
    }

    override fun onDestroy() {
        super.onDestroy()
        isServiceConnected.value = false
        if (instance == this) {
            instance = null
        }
        unregisterController()
        mediaSessionManager?.removeOnActiveSessionsChangedListener(sessionsChangedListener)
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        instance = this
        isServiceConnected.value = true
        initMediaSessionManager()
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        isServiceConnected.value = false
        if (instance == this) {
            instance = null
        }
    }

    private fun initMediaSessionManager() {
        try {
            mediaSessionManager = getSystemService(Context.MEDIA_SESSION_SERVICE) as? MediaSessionManager
            val component = ComponentName(this, MediaListenerService::class.java)
            mediaSessionManager?.addOnActiveSessionsChangedListener(sessionsChangedListener, component)
            val controllers = mediaSessionManager?.getActiveSessions(component)
            updateActiveController(controllers)
        } catch (e: SecurityException) {
            Log.w(TAG, "Notification listener permission not granted yet: ${e.message}")
        } catch (e: Exception) {
            Log.e(TAG, "Error initializing media session manager: ${e.message}")
        }
    }

    private fun updateActiveController(controllers: List<MediaController>?) {
        if (controllers.isNullOrEmpty()) {
            _currentSongFlow.value = null
            return
        }

        // Prefer Spotify first, otherwise pick the first actively playing session
        val spotify = controllers.firstOrNull { it.packageName == SPOTIFY_PACKAGE }
        val playingController = controllers.firstOrNull {
            it.playbackState?.state == PlaybackState.STATE_PLAYING
        }
        val target = spotify ?: playingController ?: controllers.first()

        if (activeController?.sessionToken != target.sessionToken) {
            unregisterController()
            activeController = target
            registerController(target)
        }
        extractSongInfo(target)
    }

    private fun registerController(controller: MediaController) {
        val callback = object : MediaController.Callback() {
            override fun onMetadataChanged(metadata: MediaMetadata?) {
                extractSongInfo(controller)
            }

            override fun onPlaybackStateChanged(state: PlaybackState?) {
                extractSongInfo(controller)
            }

            override fun onSessionDestroyed() {
                unregisterController()
                _currentSongFlow.value = null
            }
        }
        controllerCallback = callback
        controller.registerCallback(callback)
    }

    private fun unregisterController() {
        controllerCallback?.let {
            activeController?.unregisterCallback(it)
        }
        controllerCallback = null
        activeController = null
    }

    private fun extractSongInfo(controller: MediaController) {
        val metadata = controller.metadata
        val playbackState = controller.playbackState

        val title = metadata?.getString(MediaMetadata.METADATA_KEY_TITLE)
            ?: metadata?.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE)
            ?: "Unknown Track"
        val artist = metadata?.getString(MediaMetadata.METADATA_KEY_ARTIST)
            ?: metadata?.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE)
            ?: "Unknown Artist"
        val album = metadata?.getString(MediaMetadata.METADATA_KEY_ALBUM) ?: ""
        val durationMs = metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L

        val isPlaying = playbackState?.state == PlaybackState.STATE_PLAYING
        val positionMs = playbackState?.position ?: 0L
        val lastUpdate = playbackState?.lastPositionUpdateTime ?: SystemClock.elapsedRealtime()

        val info = SongInfo(
            title = title,
            artist = artist,
            album = album,
            durationMs = durationMs,
            positionMs = positionMs,
            lastPositionUpdate = lastUpdate,
            isPlaying = isPlaying,
            packageName = controller.packageName
        )
        _currentSongFlow.value = info
    }

    companion object {
        private const val TAG = "MediaListenerService"
        const val SPOTIFY_PACKAGE = "com.spotify.music"

        var instance: MediaListenerService? = null
            private set

        val isServiceConnected = MutableStateFlow(false)

        private val _currentSongFlow = MutableStateFlow<SongInfo?>(null)
        val currentSongFlow: StateFlow<SongInfo?> = _currentSongFlow.asStateFlow()

        private var volumeFade: VolumeFade? = null

        fun refreshSessions(context: Context) {
            instance?.let { service ->
                try {
                    val component = ComponentName(context, MediaListenerService::class.java)
                    val controllers = service.mediaSessionManager?.getActiveSessions(component)
                    service.updateActiveController(controllers)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to refresh sessions: ${e.message}")
                }
            }
        }

        fun pausePlayback(context: Context) {
            Log.d(TAG, "pausePlayback() triggered across all channels")
            val mediaSessionManager = context.getSystemService(Context.MEDIA_SESSION_SERVICE) as? MediaSessionManager
            val component = ComponentName(context, MediaListenerService::class.java)

            // 1. Fresh query of active sessions to pause EVERY media session
            val controllers = try {
                mediaSessionManager?.getActiveSessions(component)
            } catch (e: Exception) {
                Log.w(TAG, "getActiveSessions failed: ${e.message}")
                null
            }

            controllers?.forEach { controller ->
                Log.d(TAG, "Pausing session for package: ${controller.packageName}")
                try {
                    controller.transportControls.pause()
                } catch (e: Exception) {
                    Log.w(TAG, "transportControls.pause failed for ${controller.packageName}: ${e.message}")
                }
                try {
                    controller.transportControls.stop()
                } catch (e: Exception) {
                    Log.w(TAG, "transportControls.stop failed for ${controller.packageName}: ${e.message}")
                }
                try {
                    val eventDown = KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PAUSE)
                    val eventUp = KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PAUSE)
                    controller.dispatchMediaButtonEvent(eventDown)
                    controller.dispatchMediaButtonEvent(eventUp)
                } catch (e: Exception) {
                    Log.w(TAG, "dispatchMediaButtonEvent failed for ${controller.packageName}: ${e.message}")
                }
            }

            // 2. ActiveController fallback
            instance?.activeController?.let { controller ->
                try {
                    controller.transportControls.pause()
                    controller.transportControls.stop()
                } catch (e: Exception) {
                    Log.w(TAG, "activeController pause failed: ${e.message}")
                }
            }

            // 3. Targeted Spotify MediaButtonReceiver intent
            try {
                val spotifyMediaButtonIntent = Intent(Intent.ACTION_MEDIA_BUTTON).apply {
                    setComponent(ComponentName(SPOTIFY_PACKAGE, "com.spotify.mediasession.mediasession.receiver.MediaButtonReceiver"))
                    putExtra(Intent.EXTRA_KEY_EVENT, KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PAUSE))
                }
                context.sendBroadcast(spotifyMediaButtonIntent)

                val spotifyMediaButtonUpIntent = Intent(Intent.ACTION_MEDIA_BUTTON).apply {
                    setComponent(ComponentName(SPOTIFY_PACKAGE, "com.spotify.mediasession.mediasession.receiver.MediaButtonReceiver"))
                    putExtra(Intent.EXTRA_KEY_EVENT, KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PAUSE))
                }
                context.sendBroadcast(spotifyMediaButtonUpIntent)
            } catch (e: Exception) {
                Log.w(TAG, "Targeted Spotify MediaButton intent failed: ${e.message}")
            }

            // 4. Spotify internal broadcast actions
            try {
                context.sendBroadcast(Intent("com.spotify.playbacknotifications.playbacknotifications.PAUSE").setPackage(SPOTIFY_PACKAGE))
                context.sendBroadcast(Intent("com.spotify.nowplaying.pip.PAUSE").setPackage(SPOTIFY_PACKAGE))
                context.sendBroadcast(Intent("com.spotify.mobile.android.service.action.player.PAUSE").setPackage(SPOTIFY_PACKAGE))
            } catch (e: Exception) {
                Log.w(TAG, "Spotify broadcast pause failed: ${e.message}")
            }

            // 5. System-wide AudioManager dispatchMediaKeyEvent
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            if (audioManager != null) {
                try {
                    val eventDown = KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PAUSE)
                    val eventUp = KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PAUSE)
                    audioManager.dispatchMediaKeyEvent(eventDown)
                    audioManager.dispatchMediaKeyEvent(eventUp)
                } catch (e: Exception) {
                    Log.w(TAG, "dispatchMediaKeyEvent failed: ${e.message}")
                }
            }
        }

        fun requestPermanentAudioFocus(context: Context): AudioFocusRequest? {
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return null
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                        .setAudioAttributes(
                            AudioAttributes.Builder()
                                .setUsage(AudioAttributes.USAGE_MEDIA)
                                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                                .build()
                        )
                        .build()
                    val result = audioManager.requestAudioFocus(request)
                    Log.d(TAG, "Requested permanent AUDIOFOCUS_GAIN, result: $result")
                    return request
                } else {
                    @Suppress("DEPRECATION")
                    audioManager.requestAudioFocus(null, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN)
                }
            } catch (e: Exception) {
                Log.w(TAG, "requestPermanentAudioFocus failed: ${e.message}")
            }
            return null
        }

        @Synchronized
        fun setVolumeRatio(context: Context, ratio: Float) {
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
            try {
                val currentVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
                val fade = volumeFade ?: VolumeFade(currentVolume).also { volumeFade = it }
                val targetVolume = fade.targetVolume(ratio, currentVolume)
                if (targetVolume < currentVolume) {
                    audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, targetVolume, 0)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Unable to adjust stream volume: ${e.message}")
            }
        }

        @Synchronized
        fun restoreVolume(context: Context) {
            val original = volumeFade?.originalVolume ?: return
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
            try {
                audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, original, 0)
                volumeFade = null
                Log.d(TAG, "Restored original volume to: $original")
            } catch (e: Exception) {
                Log.w(TAG, "Unable to restore stream volume: ${e.message}")
            }
        }
    }
}
