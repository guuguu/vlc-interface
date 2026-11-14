# vlc interface

A personal Android media player (phone, Android 13+, built and tested for Android 17) with its own interface on top of VLC's engine (libVLC).

## Getting the APK

Every push to `main` builds an APK (Actions → latest run → Artifacts).
Pushing a tag like `v0.1.0` also publishes it as a GitHub Release:

```
git tag v0.1.0 && git push origin v0.1.0
```

Download the `.apk` from the Release on your phone and open it (allow installing from your browser/files app once).

## What's in 0.1

- Library: **Internal** (phone + SD card, manual rescan), **LAN** (SMB/NAS, NFS, SFTP, FTP, DLNA presets; servers found on the LAN; SMB auto-detect from any pasted path), **Internet** (remote storage over SFTP / WebDAV / FTP(S) / HTTP index, internet radio with search + favourites, open-a-link and M3U/IPTV lists)
- Player: gestures (volume, brightness, double-tap seek, hold for 2×), audio & subtitle tracks, subtitle download (OpenSubtitles.com, needs your own free API key + account in Settings), subtitle style, subtitle and sound sync panels (see-through overlay in landscape, below the video in portrait), PiP, resume
- Sound output sheet from every Library screen, the player and the mini-player
- Export / import of the whole setup as one JSON file (passwords optional, encrypted with a passphrase)

## Known limits in 0.1

- Switching output uses Android's own media output switcher; forcing the phone speaker while headphones are plugged in is not available yet.
- Subtitle fonts are the ones built into Android (Roboto, Condensed, Medium, Noto Serif, Mono).
- Downloaded subtitles are kept inside the app (not yet written back next to the movie on the NAS).
- The APK is signed with a **test key committed in `keystore/`** so updates install over each other. Keep this repo private, and replace the key before sharing the app with anyone.

## Build locally

JDK 17 + Android SDK, then `gradle :app:assembleRelease` (Gradle 8.11.1).
