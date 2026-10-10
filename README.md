# Spotify Sleep Timer

Let Spotify finish the track you are listening to, then pause playback. This repository contains a native Android app and a Python desktop timer.

> This is an unofficial project and is not affiliated with, sponsored by, or endorsed by Spotify.

| Component | Current version | Playback control |
| --- | --- | --- |
| Android app | 1.0.2 | Android media session |
| Python desktop timer | 1.0.1 | Spotify Web API |

## Android app

The native app runs a foreground timer and watches the active media session. When the countdown ends, it can wait for the current track to finish before pausing. With that option enabled, an optional 20-second fade runs during the last part of the track. Without it, the fade starts after the countdown and playback pauses when the fade finishes. The fade lowers the device's current media volume and never raises it.

Features include a live notification, timer extensions, immediate pause and cancel actions. It works with Spotify Free or Premium and does not need a Spotify developer app or OAuth setup.

### Build and install

Requirements: Android Studio or Android SDK, JDK 17, and Android SDK Platform 36.

From the repository root, build a debug APK:

~~~powershell
cd android
.\gradlew.bat :app:assembleDebug
~~~

The APK is created at android/app/build/outputs/apk/debug/app-debug.apk. Install it with ADB:

~~~powershell
adb install -r app\build\outputs\apk\debug\app-debug.apk
~~~

On macOS or Linux, run the wrapper with:

~~~sh
cd android
./gradlew :app:assembleDebug
~~~

You can also open the android folder in Android Studio and build the app configuration.

On first launch, grant Spotify Sleep Timer notification-listener access so Android can expose the active media session. Allow app notifications when prompted so the timer's foreground notification and controls can appear.

## Python desktop timer

The desktop script waits for a countdown, then monitors the active Spotify track and pauses playback when that track ends or the next track starts. It can control the active Spotify Connect device.

### Requirements

- Python 3
- A Spotify Developer app
- Spotify Premium for the Web API pause command

Create a Spotify app in the [Spotify Developer Dashboard](https://developer.spotify.com/dashboard) and register this exact redirect URI:

~~~text
http://127.0.0.1:8080/callback
~~~

Create a virtual environment, install the Python dependencies, and create a local environment file:

~~~powershell
python -m venv .venv
.\.venv\Scripts\Activate.ps1
python -m pip install -r requirements.txt
Copy-Item .env.example .env
~~~

On macOS or Linux, activate the environment with:

~~~sh
source .venv/bin/activate
~~~

Open .env and enter the Client ID and Client Secret from your Spotify app. Keep this file private; it is ignored by Git.

Start a 15-minute timer or choose a different duration:

~~~powershell
python sleep_timer.py
python sleep_timer.py --minutes 30
python sleep_timer.py --minutes 15 --poll 5
~~~

On first use, a browser opens for Spotify authorization. The script requests only user-read-playback-state and user-modify-playback-state. Spotipy saves its OAuth cache in .spotify_cache; that local file is ignored by Git as well.

## Behavior and limitations

- Track-end detection follows media-session or Web API updates. A track transition can be reported after the next track has begun, and Spotify features such as DJ or continuous mixes may transition before the previous track's audio has fully stopped.
- The desktop timer polls Spotify near the end of a track. Its pause timing depends on API updates, the active device, and the selected --poll interval.
- The Android app needs notification-listener access to observe playback. Its foreground service and wake lock improve timer reliability, but Android power-management settings or device manufacturers may still stop background work.
- The optional Android fade changes device media volume, not Spotify's audio stream.
- The included unit tests cover the fade calculation; playback and audio behavior should still be checked on the target Android device.

## Development

Run the Android unit tests and assemble the debug app:

~~~powershell
cd android
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug --no-daemon
~~~

The Python dependencies are listed in requirements.txt.

## License

No license file is provided for this project's original source code. The Gradle wrapper is third-party tooling under Apache License 2.0; see [Third-party notices](THIRD_PARTY_NOTICES.md).
