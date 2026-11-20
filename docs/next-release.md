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

## Switches sized with the text (reported 2026-11-20 21:25)
- Every on/off switch (settings pages, wizard, alarm pages, the wide alarm switches) now grows and shrinks with the
  text next to it: settings "Text size" × the phone's font size (limited to 70 %–180 %), taking matching room on
  its row, so a switch is never tiny beside big text or oversized beside small text.
- All switches one third smaller than before (2/3 of the old size), then scaled with the text as above
  (requested 2026-11-20 21:26).

## Choices stay where they were left (requested 2026-11-20 21:28)
- Settings pages: the choice buttons of a page (Look: player rows Songs / Radio / Both, hiding Clock / Picture /
  Both, switch colour On / Off / Both, accent colour Files / Radio / Both; Export & import: the chosen categories)
  show the last choice when the page is opened again — also after restarting the app.
- Alarms: the last alarm set is the template for the next new one — everything (name, time, repetition and day
  disks, sound source, stations, volumes, fade-in, snoozes, ringing time, output) except the calendar choices: the
  start date and the specific dates start empty (a "specific dates" alarm gives a one-time template). The very
  first alarm uses the defaults (confirmed 2026-11-20 21:29).

## Video picture quality: decoding artifacts (checked 2026-11-20 21:31)
- Full deblocking: the engine skipped the de-blocking filter, which showed square blocks and smeared edges on
  every video decoded in software (codecs or files the phone's hardware decoder refuses, and after a hardware
  decoder error). Now always on.
- Full 32-bit colour for pictures shown through the app's own display path (software decoding, Hue & sat): no
  16-bit colour banding on skies, gradients and dark scenes.
- Small videos are enlarged to the screen edges and the format sizing no longer reloads the video (see above).
- New on Settings → Video, section "Picture" (requested 2026-11-20 21:33, text helpers):
  - Video decoding: Automatic (default) / Hardware / Software. Automatic is set by each video's encoding
    (requested 21:33): the phone's hardware decoder when it can decode that codec at that size (checked against the
    phone's decoders), otherwise software; if the hardware fails on a video it switches to software at once.
  - Deinterlacing: Automatic (default) / On / Off. Automatic by encoding: on for TV-style videos (MPEG-2, DV,
    .ts / .m2ts / .mts / .vob / .mpg at TV sizes), off for the rest so they keep the fastest hardware path.
  - What Automatic learns about a file is kept, so it opens the right way at once next time; a file is re-opened
    at most once per run (no loops).
  - Changing either reopens the playing video at the same spot.
- Image quality first (requested 2026-11-20 21:34): the decoder never skips frames or decoding steps to save time;
  the fast direct hardware path is kept whenever nothing needs the frames (no colour filter, no deinterlacing);
  deinterlacing uses yadif 2× (every field shown: full, smooth motion instead of half).
- Settings → Video shows what the playing video uses right now (requested 2026-11-20 21:36): under each choice a
  line "Now: Hardware / Software" and "Now: On / Off", with "(chosen automatically)" on Automatic and the video's
  encoding and size (e.g. H264 1920×1080); "Now: no video playing" otherwise. Updates while the page is open.

## Video details on a long press (requested 2026-11-20 21:38)
- Long press on a playing video: a pop-up over the video with all its details, in fixed sections and order, label
  on the left and value on the right (tap a line to copy it, "Copy all" for everything; refreshed every 2 s):
  - File: name, title, type (container), size, duration, folder, place on the phone.
  - Source: source name, kind (phone / SMB with its version / SFTP / web), address (no password), VPN, user.
  - Video: codec (name and code), resolution with its name (SD / HD 720p / Full HD / QHD / 4K / 8K), pixel aspect,
    picture format (16:9, 2.39:1 …; "shape" renamed "format" 21:42), original orientation (portrait / landscape as recorded and how it is stored:
    as recorded, turned 90° / 180° / 270°, mirrored — added 21:41), frame rate, bitrate, profile / level, decoder and deinterlacing
    (automatic or not), colour filter.
  - Audio (each track, the playing one marked): codec, language, name, channels (5.1 …), sample rate, bitrate.
  - Subtitles: each track (language, name, format, the shown one marked), added files, delay.
  - Tags: title, artist, album, date, genre, description, encoded by, copyright, language.
  - Playback: position, speed, sound output, audio delay, data rate now, data read, frames decoded / shown / lost,
    damaged data blocks.
- The long press no longer gives 2× speed (the speed button does that); "Hold for 2× speed" is removed from
  Settings → Playback & gestures.
