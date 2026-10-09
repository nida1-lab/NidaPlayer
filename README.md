# NidaPlayer

NidaPlayer is a phone-first, local Android music player. Its interface takes inspiration from modern music-discovery apps, while playback is limited to audio already available on the device.

## Current prototype

- Dark home screen with large artwork cards and quick playback actions
- Explore screen for searching local songs, artists, and albums
- Library tabs for songs, albums, and artists
- Collection detail pages with play and shuffle actions
- Persistent mini-player and dedicated now-playing screen
- Media3 / ExoPlayer playback with previous, play/pause, next, seek, and queue display
- Embedded album artwork in the player, with generated fallback artwork
- Recent-play history stored locally on the device
- MediaSession metadata includes artwork for compatible notification and lock-screen controls
- Safety-first output behavior: audio is muted when a private listening output (headphones/headset) is not detected
- GitHub Actions builds a debug APK for testing

## Build and install

1. Open the repository's **Actions** tab.
2. Open the latest successful **Android APK** run.
3. Download the `NidaPlayer-debug` artifact and install the APK on an Android device.

CI uses JDK 17 and Gradle 8.9 to match the Android Gradle Plugin 8.7.3 build configuration.

## Important limitations

- This is an early, local-library prototype. It does not stream music from YouTube Music or any online service.
- Device output routing varies by Android version and manufacturer. The automatic safety mute should be tested on each device before relying on it.
- Embedded artwork may not exist in every file; NidaPlayer generates a local placeholder when no cover is available.
- The UI is custom-built from Android views and is still subject to device testing and iterative fixes.
