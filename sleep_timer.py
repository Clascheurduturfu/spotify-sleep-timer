"""
Spotify Sleep Timer
--------------------
Waits N minutes, then lets the CURRENT track finish playing before pausing
Spotify — instead of cutting the song off mid-way like the built-in
Spotify sleep timer does when combined with a fixed duration.

Usage:
    python sleep_timer.py                 # defaults to 15 minutes
    python sleep_timer.py --minutes 30     # custom duration
    python sleep_timer.py --minutes 15 --poll 5   # custom poll interval (seconds)

First run will open a browser window for Spotify login/consent (one-time,
then cached in .spotify_cache).
"""

import argparse
import sys
import time
from datetime import datetime, timedelta

import spotipy
from spotipy.oauth2 import SpotifyOAuth
from dotenv import load_dotenv
import os

load_dotenv()

SCOPE = "user-read-playback-state user-modify-playback-state"


def get_client():
    client_id = os.getenv("SPOTIFY_CLIENT_ID")
    client_secret = os.getenv("SPOTIFY_CLIENT_SECRET")
    redirect_uri = os.getenv("SPOTIFY_REDIRECT_URI", "http://127.0.0.1:8080/callback")

    if not client_id or not client_secret:
        print("ERROR: SPOTIFY_CLIENT_ID / SPOTIFY_CLIENT_SECRET missing.")
        print("Copy .env.example to .env and fill in your Spotify app credentials.")
        sys.exit(1)

    auth_manager = SpotifyOAuth(
        client_id=client_id,
        client_secret=client_secret,
        redirect_uri=redirect_uri,
        scope=SCOPE,
        cache_path=".spotify_cache",
        open_browser=True,
    )
    return spotipy.Spotify(auth_manager=auth_manager)


def wait_until(target_time: datetime, label: str):
    while True:
        remaining = (target_time - datetime.now()).total_seconds()
        if remaining <= 0:
            return
        chunk = min(remaining, 30)
        mins_left = max(remaining, 0) / 60
        print(f"\r{label}: {mins_left:5.1f} min remaining...", end="", flush=True)
        time.sleep(chunk)


VERSION = "1.0.1"


def main():
    parser = argparse.ArgumentParser(description="Spotify sleep timer that waits for the song to end.")
    parser.add_argument("--minutes", type=float, default=15, help="Minutes to play before winding down (default: 15)")
    parser.add_argument("--poll", type=float, default=3, help="Seconds between playback checks near track end (default: 3)")
    args = parser.parse_args()

    sp = get_client()

    end_of_window = datetime.now() + timedelta(minutes=args.minutes)
    print(f"Spotify Sleep Timer v{VERSION}")
    print(f"Timer started at {datetime.now().strftime('%H:%M:%S')}")
    print(f"Will start winding down at {end_of_window.strftime('%H:%M:%S')} (after current track finishes)\n")

    wait_until(end_of_window, "Playing")
    print("\n\nTime's up — waiting for the current track to finish...")

    target_track_id = None
    target_track_name = None
    target_artists = None

    while True:
        try:
            playback = sp.current_playback()
        except Exception as e:
            print(f"\nNetwork/API error fetching playback: {e}. Retrying in 2s...")
            time.sleep(2)
            continue

        if not playback or not playback.get("is_playing"):
            print("\nNothing is currently playing (or playback is already paused). Exiting.")
            return

        item = playback.get("item")
        if not item:
            print("\nNo track info available (might be an ad or podcast). Exiting.")
            return

        current_track_id = item.get("id")
        current_name = item.get("name", "current track")
        current_artists = ", ".join(a["name"] for a in item.get("artists", []))
        device_id = playback.get("device", {}).get("id")

        # First reading after window expired: lock in the track we are waiting for
        if target_track_id is None:
            target_track_id = current_track_id
            target_track_name = current_name
            target_artists = current_artists

        # If track has changed, it means the target track just finished and the next started!
        if current_track_id != target_track_id:
            try:
                sp.pause_playback(device_id=device_id)
                print(f"\nTarget track '{target_track_name}' finished! Paused at start of next track.")
            except spotipy.exceptions.SpotifyException as e:
                print(f"\nCouldn't pause automatically ({e}). Please pause manually.")
            return

        progress_ms = playback.get("progress_ms", 0)
        duration_ms = item.get("duration_ms", 0)
        remaining_ms = duration_ms - progress_ms

        if remaining_ms <= 1500:  # close enough to the end of the song
            try:
                sp.pause_playback(device_id=device_id)
                print(f"\nPaused Spotify right at track end: '{target_track_name}' — {target_artists}")
            except spotipy.exceptions.SpotifyException as e:
                print(f"\nCouldn't pause automatically ({e}). Please pause manually.")
            return

        remaining_sec = remaining_ms / 1000
        print(f"\r'{target_track_name}' — {target_artists}: {remaining_sec:5.1f}s left in track...", end="", flush=True)

        # Poll sleep: balance responsiveness with not hammering the API
        time.sleep(min(args.poll, max(remaining_sec - 1.2, 0.5)))


if __name__ == "__main__":
    try:
        main()
    except KeyboardInterrupt:
        print("\nSleep timer cancelled.")
