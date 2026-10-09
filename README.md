# NidaPlayer

NidaPlayer is an Android-first local music player prototype.

## Features in this initial build
- Scans local music using Android MediaStore
- Plays music with AndroidX Media3 / ExoPlayer
- Reads embedded album-cover artwork for the in-app player
- Uses MediaSession for system media controls and media notification integration
- Safety-first output rule: playback is muted when a private listening output (headphones/headset) is not detected
- GitHub Actions builds a debug APK on pushes to `main`

## Build
The GitHub Actions workflow builds `app/build/outputs/apk/debug/app-debug.apk`. Open the Actions tab and download the `NidaPlayer-debug` artifact from a successful run.

## Important
This is an early prototype. Output-device detection differs between Android devices; test the mute behavior carefully before relying on it. The current version does not yet provide a settings override for the safety mute.
