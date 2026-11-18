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
- Music or radio already playing when an alarm rings: the music stops completely and only the phone's alarm
  sound plays (the alarm tone chosen), not the alarm's music. Replaces "Music already playing: fades into the alarm".
