# Next release: requests kept

## Clock display: time zone + weather (requested 2026-11-18)
- On the clock display: a small weather icon with 4 stages only: cloudy, sunny, rainy, storm.
  Only the icon and a short identifier for the city (no other weather text).
- Time zone shown on the clock display.
- Everything is based on a location chosen by hand (city picked in the settings), not the phone's position:
  the clock's time zone and the weather status follow it, also when the phone's time zone is not on automatic.
- Weather: find a weather report feed and connect to it for the chosen city.
- The weather icon + city and the time zone are never overlapped by the hour / minute hands, nor by the date
  (placed in a free part of the face, like the date already is).

## Alarm (requested 2026-11-18)
- Radio as the alarm: never use the network buffer (play live, no buffered start).
- After a snooze: each new ring plays a different song, from a different part of it (not the start of the same one).

## Visualisation (requested 2026-11-18)
- A button under the clock button (same left column, player buttons stay centred, never overlapping) starts a
  full-screen visualisation: only colours dancing and blending to the music's rhythm.
- Colour combinations set in a new Settings section named "Visualisation".
- Visualisation brightness setting (same section).
- Visualisation screen keeps the display on; double tap anywhere on the screen exits.
- On the visualisation screen, sliding a finger in any direction changes the screen brightness (up / right brighter, down / left dimmer).
- The visualisation screen keeps the display on; a double tap on the screen exits it.
- Music or radio already playing when an alarm rings: the music stops completely and only the phone's alarm
  sound plays (the alarm tone chosen), not the alarm's music. Replaces "Music already playing: fades into the alarm".
- Setting: maximum level of an alarm (volume cap no alarm ever goes above: target, wake-check restart, alarm tone).

## Bluetooth SBC at maximum quality (asked 2026-11-19): ideas for the next iteration
- An app can't change the Bluetooth codec or the SBC bitpool: that is a system-only call (privileged).
  Android's stock SBC tops out at bitpool 53 (~328 kbps, "high quality" joint stereo); SBC XQ / dual channel
  exists only on some phone ROMs.
- What the app can do:
  - Read the active codec (Android 13+, BluetoothA2dp codec status): show "SBC · 44.1 kHz · 16-bit" in the
    sound-output sheet, and warn when the link has fallen back to a lower quality.
  - Shortcut to Developer options → Bluetooth audio codec / sample rate / bits / LDAC quality ("Best effort" →
    "Optimised for audio quality").
  - Send audio at the rate the Bluetooth link uses (44.1 or 48 kHz), so the phone doesn't resample a second
    time; keep the peak limiter so SBC never gets clipped input.
  - Optional "Bluetooth quality" mode: no other app sounds mixed in, no volume steps below 100 % in the app's
    digital chain (SBC loses detail at low digital levels — use the device volume instead).

## Alarm sources (requested 2026-11-19)
- Alarms play only music stored on the phone or a radio station (no NAS / network songs, no queue / playlists from the network).
- Music: when an alarm is set up, ask for the local music root folder unless it's already known from the audio player setup.
- Radio: ask for a preferred station and a fallback station. Preferred not reachable → fallback station;
  fallback not reachable either → alarm sound.
- Alarm sound chosen in settings: the default or any other available one, each previewed for 3 seconds when picked.
- Every one of these choices is explained to the user while setting the alarm up.

## Alarm certainty (2026-11-19)
- Alarm health check: a warning on the alarm page and on the clock area when anything could block an alarm
  (exact alarms off, full-screen alarm off, battery Restricted, notifications off, phone maker sleep lists),
  with a fix button for each.
- "Test alarm in 1 minute" button (works after Exit with the phone locked).
- Alarm settings: double tap or long-press any setting → an explanatory pop-up for that setting.
