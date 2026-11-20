# Next release: requests kept

## Player switch buttons go straight to the player (requested 2026-11-20 18:06)
- "Songs" / "Radio" / "Video" in a player's header always change directly to that player — never another page.
- It plays what that player last played (kept across app restarts too), else the most recent song / video, else
  (radio) the first favourite station. If there's truly nothing (never played any), a short note says so and the
  current player stays.

## Radio player's "Find stations" (reported 2026-11-20 18:08)
- The ✕ only emptied the name (results stayed, so it seemed to do nothing). Now ✕ closes the search: name and
  results cleared, the find box shrinks back to its small size, the keyboard goes away.
- The ▲ ▼ arrows are removed from this box; the result counter stays (requested 2026-11-20 18:09).
- The results list is tighter: no extra gap between stations (only the thin grey line), slimmer rows with the
  name and its details close together (requested 2026-11-20 18:09).
- In front of each station in the results: its country's flag, or a globe picture when the country isn't known
  (requested 2026-11-20 18:11).
- Unknown country: instead of the plain 🌐, a globe turned to where the station's server is (requested 2026-11-20 18:13):
  Europe · Africa · North America · South America · Asia · Australia globes (Africa, Australia added 18:16;
  Americas split 18:17).
  - Where: the station directory's own coordinates when it has them; otherwise the server's address (its IP) looked up
    once with a free geolocation service (ipwho.is), kept so each server is asked only once.
  - 🌍 (Europe) by default: while it's being looked up and when nothing can be found — no plain 🌐 (requested 2026-11-20 18:14).
- Globes drawn from real coastlines (shaded, light from the upper left), not emoji: Europe-centred (Africa pushed to
  the bottom edge), Africa, North America, South America, Asia, Australia; shipped as small pictures in the app (shown 2026-11-20 18:15).

## Song / radio player header: no more colliding buttons (reported 2026-11-20 18:20)
- Upright: two rows. Row 1 — back, home, settings, find song, Shazam, background-work sign, screen-on sign, exit,
  spread evenly across the bar. Row 2 — the other two player buttons (Songs / Radio / Video) on the left and the
  output pill on the right, with room for its full name.
- Sideways: one row, everything spread evenly from edge to edge (same as the video bar).
- Player buttons slightly bigger (13 sp, more padding) with even spacing between them.

## Settings pages: back to the parent page, red ✕ back to the player (requested 2026-11-20 18:23)
- On every settings sub-page (each settings group, Sound settings, Find media folders, Export & import, Alarm clock
  settings, Clock aspect): swipe right anywhere on the page, or pull down further when already at the top, to go back
  to the parent page (the settings page it was opened from, else the main Settings page).
- Main Settings page and all sub-pages: a white ✕ on a red square in the top-right corner (where the gear was)
  goes straight back to the player that was open (songs / radio or video); when no player was open, to the app's
  main page.
- The home symbol stays as it is: it goes to the app's main page.

## Weather symbol missing on the clock (reported 2026-11-20 20:49)
- Since 1.48 (Now / At noon) the weather was fetched and kept, but the clock looked it up under the old name, so the
  icon never appeared (city and time zone still showed). The clock now finds it again; changing Now / At noon
  refreshes it at once.

## Small videos fill the screen; every format grows to the edges (requested 2026-11-20 20:55 / 20:56)
- A video smaller than the screen (low resolution) is no longer shown at its own small size in the middle: it's
  enlarged as much as possible, until it reaches the edges of the video area (full width upright, full height or
  width sideways).
- Whatever format is chosen with the format button, the picture grows until an edge of the screen is reached:
  Fit and 1:1 keep the video's own shape, 16:9 and 4:3 use that shape, Fill covers the whole area (stretched),
  Crop covers the whole area and cuts off what sticks out.
- Changing the format no longer restarts the video for a moment; it changes at once.

## Video player: buttons never overlap (requested 2026-11-20 21:05)
- The buttons over the video are stacked top bar → centre buttons (−10, play/pause, +10) → bottom row, each in its
  own space, so they can never cover each other, in any orientation or video size.
- Upright with the video on top (wide films, or tall videos on two thirds): only the symbols row stays over the
  picture. Output, Audio & subs, Hue & sat, speed, Songs and Radio move under the video, next to Pause (one wrapping
  row, no duplicates).

## Song / radio player: sine button to the tone sliders, slimmer Sleep (requested 2026-11-20 21:09)
- Right of the player buttons, mirroring the clock / visualisation buttons on the left (same round size, look and
  spacing): on top a new button with a sine wave, under it Sleep as a round button with the word "Sleep" inside,
  shrunk to fit with a clear margin inside the circle (never touching its edge), so the order, sizes and spacing never break (ringed in the accent colour while a sleep timer is set)
  — symmetry requested 2026-11-20 21:11, word kept and auto-sized 21:12.
- The sine button scrolls the page to the tone sliders: they end up in the middle of the free space (under the
  title block upright); when they're near the end of the page, it scrolls as far as the page goes.
- Shown only while the tone sliders are on (Settings) and their row is in the player.

## No cover → the visualisation in its place (requested 2026-11-20 21:16)
- In the song and radio players, when no picture is found (none in the file or folder, none online, no station
  logo), the visualisation plays in the picture's place, filling exactly the square a cover takes (square corners, no rounding), with the visualisation
  settings currently chosen (effect, colours, brightness, …), moving with the music.
- Only after the search for a picture has finished (no flash of it while still looking); between songs the place
  stays empty as before.
- It is the cover for every rule (requested 2026-11-20 21:19): it scrolls with the page, is taken away after
  scrolling out and comes back after the set seconds with the "Hide the album picture while scrolling" switch,
  stays when that switch is off, follows the player rows order, and shrinks to fit sideways — exactly like a cover.
- Long press on it: opens the Visualisation settings page (patterns, colours, frequencies); the red ✕ there goes
  straight back to the player (requested 2026-11-20 21:20).

## Setting: visualisation instead of album pictures (requested 2026-11-20 21:22)
- Settings → Visualisation, at the top: "Visualisation instead of album pictures" — Off / Songs / Radio / Both.
  Songs: the song player always shows the visualisation in the album picture's place; Radio: the radio player in
  the station logo's place; Both: the two. Off (default): pictures as usual (the visualisation still fills in when
  no picture is found).
- Same place, size and rules as the picture (scrolling, hiding, row order, sideways); long press opens the
  Visualisation settings; the clock, when on, still takes that place.
- Text helper (hold the setting; also pops up on a change when Helpers are on).
- Clock button has priority (confirmed 2026-11-20 21:24): clock on → the clock takes the place (instead of the
  visualisation or the picture); clock off with its button → what was there before comes back at once: the
  visualisation (setting on, or no picture found) or the album picture / station logo, shown from the top.
