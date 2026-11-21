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

## Everything per video file, forgotten when the file is gone (requested 2026-11-20 21:44 / 21:47)
- Settings → Video → Picture while a video is playing: Decoding and Deinterlacing choices are for that video file
  only ("For this video (kept with the file)"), with "Default for other videos: …" shown under them. With no video
  playing they set the default for all videos.
- Kept per video file: decoder and deinterlacing chosen by hand, what Automatic learnt (software needed,
  interlaced or not), the picture format (Fit / Fill / Crop / 16:9 / 4:3 / 1:1), the audio and subtitle track
  chosen (found again by name, else by number) — besides what was already per file: Hue & sat, resume position,
  audio / subtitle delays.
- Hardware or Software chosen by hand on more than 5 videos becomes the default for new videos (a short note says
  so); the videos with their own choice keep it.
- Video details show where each choice comes from: automatic / chosen for this video / default.
- When a video file disappears from the library, everything kept for it is deleted: phone videos are checked a few
  seconds after the app starts; network videos when their folder is listed in Libraries and the file isn't there.
- Its subtitles are deleted automatically only on the phone and only those the app downloaded (OpenSubtitles):
  the app's copy and its undo copy (21:48 / 21:51). Subtitle files on the NAS are never deleted (21:50); copies of
  subtitles found next to a video are left alone.

## Helper for the OpenSubtitles credentials (requested 2026-11-20 21:52)
- Settings → Video → Subtitles: a "How?" button next to the OpenSubtitles text (and a long press on the text) opens
  a helper: create the free account, make the API key (profile → API consumers → New consumer), what username and
  password are for, the site's daily download limit, how the key and password are kept, and auto-download.

## Video player: current format shown, format button names the next one (requested 2026-11-20 21:53)
- The format button shows what a press will set: "→ Fill", then "→ Crop", "→ 16:9", "→ 4:3", "→ 1:1", "→ Fit"
  (the button row otherwise unchanged).
- The format shown now is written on the picture (just the value, e.g. "Fit" — 21:56; white with a soft shadow, at the right under the
  top bar); it comes and goes with the other controls (hidden when left alone or after a tap on an empty part of
  the video) — 21:55.

# Next release (after 1.52.0)

## Alarm page: no Save button, saved when leaving (requested 2026-11-20 22:11)
- The Save button on the alarm page (new or existing alarm) is gone. Leaving the page — back, home, the red ✕,
  any other page — saves the alarm when something was set by hand. Left untouched, nothing is saved: a new alarm
  isn't created, an existing one isn't saved again. "Delete alarm" still deletes it (not saved back).

## Sine button didn't scroll to the tone sliders (reported 2026-11-20 22:14)
- Its scroll was cut short by the cover / clock hiding (which also scrolled the page back up later). Now the
  page glides straight to the sliders (centred in the free space, or as far as the page goes) with the hiding
  kept out of it; when they're already where they'd go, a short note says "Tone sliders are in view".

## Sine button said "in view" while the tone sliders weren't on screen (reported 2026-11-20 22:20)
- It used remembered screen positions, which could be old. Now it measures the sliders' place inside the page at the
  moment of the tap and scrolls there; if the sliders aren't on the page at all it says so (pointing to Look →
  player rows) instead of "in view".

## Visualisation follows the music's rhythm (reported 2026-11-20 22:27)
- It followed only how loud the music was, heavily smoothed (slow rise, slower fall, a reference that took ~7 s to
  adapt), so the movement hardly changed with the beat.
- Now each band detects beats: a jump of its level above its own short average (≈ 0.4 s). Every beat fires a
  pulse — the low band (kick / bass, up to ~5 per second) and the high band (hats, snares) — that drives the
  movement and gives it a step forward; the pulse rises at once and fades in a fraction of a second. Loudness
  still adds a little. The automatic level adapts within a few seconds. Still no flashing.
- Movement speed mostly from the music (22:28, dance music felt slow): a calm drift between beats, about 3× faster
  moves on the beats than before.

## Beat-synced visualisations (requested 2026-11-20 22:38; chosen: projectM, spectrum analyser, beat engine)
- Beat engine: onsets of the bass are found against their own recent statistics; the tempo (70–180 BPM) comes from
  the spacing of recent onsets; beats keep coming on time between clear onsets (phase-locked), so every pattern
  pulses on the beat and keeps time through quieter bars.
- Spectrum analyser: one pattern, "Spectrum (centred)" — bars growing up and down from the middle line with peak
  marks (22:44: only the centred one). 32 bands from ~40 Hz to 16 kHz, measured in the app's sound engine only while
  it's shown. Uses the colour combinations like every pattern (pattern colours or the chosen ones).
- Bar thickness (Settings → Visualisation, shown for the spectrum): 3–40 dp, default 10; the bars always fill the
  width, so thicker bars = fewer bars. Helper with a live preview on a still hold.
- projectM (MilkDrop presets): planned as the next step — libprojectM (LGPL 2.1, OpenGL ES 3) built into the app and
  fed with the app's own sound, with a preset pack; its own pattern group on the Visualisation page.
- "Four of each" (22:46): four analyser pictures in the style of hi-fi analysers like audioMotion (the app's own
  drawing, from the same 32 bands; AGPL code not used): "Analyser: LED bars" (LED segments, colour climbing with
  height, peak LEDs, reflection), "Analyser: light bars" (full-height bars whose glow follows the band),
  "Analyser: wave area" (smooth filled curve, edge thickens on the beat), "Analyser: radial" (bars round a circle
  that swells on the beat and turns). Bar thickness applies to all bar pictures; colour combinations to all.
- projectM: four MilkDrop presets bundled, in the projectM step.
- LED bars, light bars and radial centred on the middle and set on the vertical axis (22:51): LED segments grow up
  and down from the middle line (peak LEDs both ways, no reflection); light bars glow from the middle line, their
  light and reach following the band; radial is fixed (no turning) with bass at the top and treble at the bottom,
  the left half mirroring the right.
- Radial: the centre is the bass colour (same as the bass bars at the top), solid and glowing brighter with the bass
  and the beat (22:53).
- LED bars and light bars turned 90° (22:54): the origin is a vertical line in the middle; each band is a row growing
  left and right from it, bass at the top and treble at the bottom; bar thickness sets the row height (thicker →
  fewer rows).
- Sensitivity (22:55): new slider for all spectrum / analyser pictures, ×0.5 … ×3 (default ×1.3); higher makes the
  bars / rows / rays / wave reach the edges of the frame. Helper with a live preview on a still hold.
- Good resolution for the visualisations (22:56): the mathematical patterns (ink, reaction, phase, Chladni,
  Mandelbrot, epicycles) are computed on a finer grid — starting at 1.6× the old one and adjusting itself to the
  phone (finer while frames are cheap, up to 3×; coarser only if drawing gets slow) — and enlarged with high-quality
  smoothing instead of the plain one. Spectrum / analyser pictures are drawn at the screen's full resolution.
- Visualisations work only when they can be seen (22:57): nothing is computed while the screen is off or the app
  isn't in the foreground (also another app / window on top), nor while the player's picture place is hidden or
  scrolled out of view; the spectrum measuring in the sound engine is switched off at the same times. It all
  resumes at once when the visualisation is visible again.
- No restart when shown again (22:58): coming back on screen the visualisation carries on where it was (same
  picture, tempo, levels; nothing rebuilt); only the sound measured meanwhile is dropped so there's no jump to an old
  level. The slow first frames after waking up don't count toward the grid adjustment, and once the grid had to go
  coarser it stays settled — so patterns aren't restarted by resizing.
- When the player shows a cover or the clock (22:59), the visualisation isn't running at all: it's removed — its
  frame loop and the spectrum measuring stop and its picture memory is freed at once; it's started fresh only when
  the visualisation is shown again in that place.
- Full-screen visualisation (23:00): the same rules (it's the same drawing) — stops when the screen is locked / off
  or the app leaves the foreground, carries on without restarting when shown again, and is removed with its memory
  freed when the full-screen page is closed.
- Full-screen brightness (23:01): Settings → Visualisation → "Full-screen brightness": Phone's own (default) or a
  level 1–100 % used while the visualisation is full screen. The brightness swipe on the full screen (left / right
  upright, up / down sideways) changes it and is kept as the setting. Leaving full screen gives the phone its own
  brightness back. Text helper.
- Only the chosen visualisations are offered (23:06): Spectrum (centred) and the four analysers (LED bars, light
  bars, wave area, radial); projectM's four follow when its library is built. A previously chosen old pattern
  (clouds, plasma, …) becomes the centred spectrum.

# After 1.56.0

## projectM (requested 2026-11-20 23:06)
- libprojectM v4.1.8 compiled for Android arm64 (OpenGL ES 3, shared, LGPL 2.1) by the "Build libprojectM" workflow
  and committed into app/src/main/cpp/projectm (library + headers + licence). Done 23:11.
- Integrated (23:26 "start"):
  - Four bundled presets from projectM's "Cream of the Crop" pack (credits in assets/projectm/CREDITS.txt), as
    choices on the Visualisation page: "MilkDrop: Tunnel", "MilkDrop: Kaleidoscope", "MilkDrop: Rings",
    "MilkDrop: Starburst" (chosen for needing no extra texture files).
  - Fed with the music heard now: the sound engine keeps the song's samples (before the volume) with their play
    time; projectM gets exactly the part being heard (Bluetooth delay included). The tap runs only while a
    MilkDrop picture is drawing.
  - Drawn on its own OpenGL ES 3 surface in the cover place and full screen; draws only while visible with the
    screen on and the app in the foreground (picture and context kept meanwhile, carries on without restarting);
    freed when a cover / the clock replaces it or the page closes. Long press still opens the Visualisation page.
  - Presets keep their own colours (colour combinations, Sensitivity, Amplitude and Bar thickness are for the
    spectrum / analyser pictures). In the pattern list, MilkDrop choices show a name tile instead of a live preview.
  - APK: libprojectM (~15 MB before packaging) is added.

## Video "Rotate" button (requested 2026-11-20 23:14)
- New "Rotate" button in the video player's bottom row (next to the format button): turns only the film's picture
  90° clockwise per press (90° → 180° → 270° → back to normal) for a video filmed or recorded the wrong way, so it
  looks humanly normal. The phone's own orientation isn't touched (the 🔄 button still does that).
- The label shows the turn ("Rotate · 90°"); the picture format is worked out for the turned picture (a sideways
  film turned upright is sized as an upright one). Kept with the video file (and forgotten with it); reopens the
  video at the same spot to apply. Video details show "Turned with Rotate".

## Spectrum / analyser "Amplitude" (requested 2026-11-20 23:25)
- New slider on Settings → Visualisation for all spectrum / analyser pictures: how much the bars jump between quiet
  and loud — ×0.4 (steady height, small ripples) … ×2.5 (from almost nothing to the full frame), default ×1.
  Works with Sensitivity (which lifts the whole movement). Helper with a live preview on a still hold.

## Lava flow + spectrum (requested 2026-11-20 23:30)
- New visualisation "Lava flow + spectrum": a lava lamp — soft blobs rising and sinking, merging and splitting when
  they meet, swelling with the bass and moving faster on the beat, with a dark glowing background — and the
  centred spectrum drawn over it, see-through (≈ 55 %). Both use the same colour combination (pattern colours: the
  lava's reds and golds, or the chosen custom colours). Bar thickness, Sensitivity and Amplitude apply to its bars.
- Lava flow + spectrum (23:32): the spectrum over the lava is on the vertical axis — rows growing left and right from
  a vertical middle line, bass at the top (row height = bar thickness). Lava flow at a finer grid: starts at 1.5×
  (135 × 240) and adjusts itself to the phone up to 3× (270 × 480), smoothly enlarged; the lava has no history, so a
  grid change never restarts it.
- Lava flow + spectrum (23:34): the bars use the lava's colour range — like a blob: the edge colour (first) at the
  tips, hotter toward the middle line, reaching the core colour (last) when the band is loud; quiet rows stay in
  the edge colours; peak marks in the edge colour.
- Lava flow + spectrum (23:36): low frequencies at the bottom of the analyser, treble at the top; red tips (bar ends
  and peak marks) whatever the colours; the lava's own colours ordered from the cool edge (deep red) to the hot core
  (gold).
- Lava flow + spectrum (23:38): the Amplitude slider also drives the lava — how far the blobs wander (smaller
  steadier, larger reaching the edges) and how much they swell with the bass — one slider for bars and lava.
- Separate sliders (23:39): "Lava amplitude" (Lava flow only: blob wander and swell, ×0.4 … ×2.5, default ×1) and
  "Bars amplitude" (the bars, = Amplitude on the other pictures). Helper with live preview, like Amplitude.
- Settings kept per visualisation pattern (23:40): colours, brightness, movement, mixing, colour speed, definition,
  frequencies, bar thickness, sensitivity and the amplitudes are remembered for each pattern; choosing a pattern
  brings back its own settings (the first time it starts from the ones in use). Full-screen brightness stays one
  setting for all.

## Live preview on the Visualisation settings page (requested 2026-11-20 23:41)
- A live preview is always shown on Settings → Visualisation: a small floating window (at first beside the pattern
  buttons, on the right) showing the chosen pattern with every setting change at once, moving with the music. Drag
  it anywhere (its place is kept); tap it to switch small / large. Replaces the 3-second preview after picking a
  pattern.
- Export / import (23:44): the setup file now carries every visualisation pattern's own settings (not only the one
  in use); importing brings them back (the file's ones replace this phone's for the same patterns).
- Visualisation is its own export category (21 Nov 01:01): "Visualisation (all patterns' settings)" next to
  "Alarms and clock"; tick it to export / import every pattern's settings and the one in use. Setup files from
  before the split (visualisation inside the alarms section) still import it.

## Problem reports (2026-11-21 01:51)
- Settings → Logs → "Collect error logs": one zip (saved where you choose) with errors only (02:03): every log
  page's failures (app-errors.txt), the system log's error / fatal lines incl. crash stacks (system-errors.txt),
  why the app ended the last times with crash / not-responding traces (exits.txt), and the phone / app details and
  settings (info.txt, no passwords or keys) — to send for a problem.
- Crash entries now also say which page was on screen and list the app's own code lines first.

## Crash fixed: app closing at the end of a ReplayGain scan (reported 2026-11-21 01:53 / 01:55)
- "Vertically scrollable component was measured with an infinity maximum height": the ReplayGain report windows
  ("ReplayGain done" and "ReplayGain scan: N problems"), which pop up over any page (the player too) when a scan
  ends, scrolled their text inside a window that already scrolls it — Android doesn't allow that and closed the
  app. The inner scrolling is removed; the window's own scrolling stays.

## Video player fixes (reported 2026-11-21 01:57 / 02:01)
- Header upright: the title is no longer squeezed between the symbols (it's shown under the video); the symbols
  (back, home, lock, settings, Shazam, background work, screen-on, exit) are spread evenly over the bar.
- Orientation: phone videos recorded upright (e.g. WhatsApp VID-…mp4) were laid out as wide videos — a short wide
  box with the upright picture small inside. The recorded rotation is now read from the file with Android's own
  reader (phone files) and the picture is laid out by its real shape (upright → the tall layout, two thirds of the
  screen, or full screen); other files keep libVLC's rotation flag, and nothing is turned twice.
- Previous / next file buttons (02:01): ⏮ and ⏭ on both sides of −10 / play / +10 go to the previous / next file of
  the list the video was opened from (its folder).
