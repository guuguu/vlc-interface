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
  settings (info.txt, no passwords or keys) — to send for a problem. Saved straight to Download/vlc_interface
  (no file picker, 02:04).
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
- Error log export made crash-proof (02:05): it runs off the screen's thread and catches every failure (also
  running out of memory), so it can't take the app down; each part of the zip is collected on its own (a part that
  fails becomes a note, the rest is still saved); the system log is read as error lines only, the latest 20 000 at
  most; the zip is written as a hidden "pending" file and only appears when complete — if anything goes wrong
  (even the app closing mid-way) no broken zip is left. Crashes themselves are always kept (crash log and Android's
  exit records), so the next export still contains them.

## For 1.58.0 — from the 1.57.0 error log (uploaded 2026-11-21 02:11)
- The 3 crashes in the log (00:44, 01:16, 01:30) are the ReplayGain-report crash already fixed in 1.57.0.
- Video with sound but no picture: between 01:59 and 02:01 libVLC repeatedly logged "can't get Video Surface" /
  "video output creation failed" / "BufferQueue has been abandoned" (Samsung A56, WhatsApp videos, shuffle). The
  picture output is made once, when a video starts; when the video view was not ready yet (video player screen
  still opening, e.g. after a song in the same list) or its surface was taken away while playing, the video went on
  with sound only. Now: when the video starts, when its view is attached / back on screen, and when the picture
  output disappears while the video plays, the app checks that a picture exists; if not, the video is re-opened at
  the same spot (once per video) and a note is written to the Logs ("VOUT").
- That case no longer marks the file as "hardware can't decode it" (it was a missing picture, not a decoder fault),
  so such files aren't pushed to software decoding for good.

## Sound engine and pitch (requested 2026-11-21 02:22)
- False alarms of the app's own sound output fixed. The log had 33 "App output gave no sound → switched to libVLC
  output" entries; they were mostly wrong:
  - "the phone did not play the sound (state 4 …)": the sound stream had been opened anew during the 4-second check
    (new song format, output change, Bluetooth reconnect) — a new stream counts its played sound from 0 again, which
    looked like "nothing played". The engine now numbers its streams and the check only compares the same stream.
  - "libVLC delivered no sound (state -1)": libVLC was between two sound outputs (track change, end of a video) or
    the song itself wasn't moving (paused, seeking, network wait). These now just get looked at again.
  - Judged a failure only when the song really moved on ≥ 2 s on the same stream with no sound. Real failures (the
    phone refusing the stream, no sound while playing) still switch to libVLC's output as before.
  - Each false alarm had restarted the song and dropped the app's sound chain (tone, loudness, EQ, limiter) for
    libVLC's plain output — a sound that changed for no reason.
- Pitch / speed: libVLC keeps the player's speed from one item to the next, so after a video watched at another
  speed the next song played faster or slower (time-stretched, with its warble) while the button showed 1×. Every
  item now starts at normal speed (1×), as shown.
- Checked again, no other pitch difference: the app's own output tells libVLC the rate the phone really opened (libVLC
  converts to it); when the phone changes output mid-song, the app's converter goes from the song's rate to the new
  one exactly (no drift); the visualisation, EQ and meters use the song's rate.

# After 1.58.0

## Whole-code check (requested 2026-11-21 02:34)
- Checked: the release build's compiler warnings (only 3 deprecation notes), the C sound / projectM / MP3 code with
  the clang analyzer (no findings), and the whole Kotlin code for the kinds of mistakes that close an app
  (scrolling inside scrolling like the ReplayGain crash, limits that can turn upside down, empty lists, forced
  values, network on the screen's thread, background errors, pictures used after being freed, damaged saved data).
- Fixed:
  - Seek bubble on a narrow player bar (narrower than the bubble) and dragging the floating play button on a
    window smaller than it: a limit could turn upside down → the app closed.
  - A network song cached ahead: looked up twice; if the cache dropped it in between, the app closed when the song
    started. Now looked up once.
  - Very long, thin album pictures (a side shrinking to 0 px when resized) → the app closed. At least 1 px now.
  - Background work (cover search, ReplayGain scanning, radio recording, weather, VPN checks, saving settings,
    alarms, playback service…): an unexpected error in one task no longer closes the whole app; the task stops and
    the error is written to Settings → Logs → Crashes as "Background error (app kept running)" with its trace.
  - Visualisation pictures were freed by hand while the last frame could still be on its way to the screen (a
    "recycled bitmap" crash risk, when closing or when the lava grid changes size). Now simply let go and freed by
    Android.
  - MusicBrainz lookups (cover search) always run off the screen's thread.
  - Alarms: one damaged part of the saved alarms (or one damaged alarm) emptied the whole alarm list, and the next
    save wrote that empty list back. Each part and each alarm is now read on its own.
  - Settings file unreadable: before defaults are used (and saved over it), it is kept aside as
    state.json.bad-<time> so libraries, playlists and settings can be recovered; noted in the Logs.
  - projectM: out of memory when creating a picture is handled (no crash).

# After 1.59.0

## Video proportions in every format (reported 2026-11-21 02:56)
- Wrong width / height proportions: sideways only Crop was right, upright only Fit. The app shaped the video box
  itself (from the size and rotation it worked out) and had libVLC stretch the picture into it; whenever the app's
  idea of the shape differed from the picture libVLC really shows (its own rotation, pixel aspect, the box size at
  the moment it measured), the picture was squeezed.
- Now libVLC sizes the picture itself in every format, as it always did for Crop: the video view covers the whole
  area and the picture is fitted with its real proportions — Fit (whole picture, to the edges), Fill (whole area,
  stretched on purpose), Crop (whole area, edges cut), 16:9 and 4:3 (that shape), 1:1 (own shape, grown to the edges
  like Fit). Small videos still grow to the edges.

## Favourite stations: tight rows with flags (reported 2026-11-21 02:58)
- The favourites list now looks like the Find stations results: no extra gap between stations (the ⋮ button no
  longer adds height; the row spacing follows Look → vertical spacing), and in front of each station its country's
  flag — read from the country code kept with the favourite (e.g. "· ES") — or, when it has none, the globe turned to
  where the station's server is (looked up once per server, Europe while looking / when nothing is found).

## Video format analysis (requested 2026-11-21 03:02, procedure 03:05)
- Settings → Logs → "Video format analysis" → Start: analysis mode on. Every control of the video player stays on
  screen (no auto-hide, a tap doesn't hide them) so the photos show the whole screen as used, and an
  "Analyse 5 videos" button (with ✕ to leave the mode) sits in the middle of the video player.
- You open the right folder and start the first video yourself, then press "Analyse 5 videos". On that video and
  the next 4 of its list, one after the other: every format (Fit, Fill, Crop, 16:9, 4:3, 1:1) in the current
  orientation, then the phone turned the other way and every format again; each step photographed and measured.
  A small note on top says the step (kept out of the photos).
- Each photo: the screen with the real video picture in its place (ordinary screenshots leave the video black),
  outlined: red = the video view, green = the surface libVLC draws on, yellow = where the picture really is (the
  non-black part). Numbers per step: screen size, the three boxes and their shapes, the surface's buffer size,
  libVLC's track size, pixel aspect, orientation flag, the file's own rotation, the Rotate button, the app's picture
  size, the format chosen / given to libVLC, libVLC's aspect ratio and zoom, the decoder; and the shape seen against
  the expected one (≈ 0 % for Fit / 1:1).
- At the end everything is packed into one zip in Download/vlc_interface
  (vlc-interface-format-analysis_<date_time>.zip: report.txt + up to 60 photos) to upload, and the player is reset
  (03:05): back to the first video at the spot it was, playing or paused as it was, each video's own format and the
  phone's orientation setting as before; analysis mode turns itself off.

# After 1.60.0

## Picture size self-check (from the format analysis uploaded 2026-11-21 03:19)
- The analysis (5 WhatsApp videos, upright 1072×1920 / 720×1280 / 478×850, 60 photos) showed the 1.60 sizing is
  right: Fit, 1:1, Fill, Crop, 16:9 and 4:3 gave exactly the shape each format calls for in 52 of 60 photos, upright
  and sideways.
- The 8 others kept a previous size: the new size libVLC set was dropped because it came while Android was already
  laying the screen out (e.g. upright 4:3 still the Fit shape, sideways 1:1 still 16:9, sideways Fit 1.5 % too wide
  after the 4:3 before it).
- Now after every change (format, turning the phone, new video, size of the video area) the app measures the video
  surface against the size the format calls for and, while they differ by more than 1 %, applies the sizing again
  with a fresh layout (every 0.35 s, up to 6 times).
- Format analysis: each photo's report now says the surface size the format calls for and OK / WRONG (the yellow
  "picture seen" box is not reliable while the controls are on screen); 2.6 s per step so the self-check has run.

# After 1.61.0

## Notification bar: a sign whenever a player is working (requested 2026-11-21 11:17, Android 12)
- The player notification was on a low-importance ("silent") channel: Android 12 and newer (Samsung by default)
  show no status-bar icon for silent notifications, so nothing in the notification bar said a player was working.
- Now on its own channel "Player working" at normal importance, but fully silent (no sound, vibration, light or
  pop-up): the ▶ icon is in the status bar and the player notification (title, artist / station, controls) in the
  notification shade while songs, a radio station or a video play; ⏸ while paused. Lock screen shows it too.
- Shown at once (Android 12 otherwise waits up to 10 s for a foreground notification), and if Android refuses the
  foreground start while the app is in the background (e.g. playback resumed by Bluetooth), it's still shown as a
  plain notification instead of not at all.
- The old "Playback" channel is removed from the app's notification settings (replaced by "Player working").

## Settings lists: drag to reorder (requested 2026-11-21 11:28 / 11:29 "on all of it")
- Every ordered list in the settings — Look → "Order of the player rows" (Songs / Radio / Both) and Look → "Order of
  the Settings links" — no longer uses ▲ ▼: drag a row by its ≡ handle (or long-press anywhere on it) up or down,
  the other rows make room as it passes, let go to keep the new order.
- No ✕ on these lists (2026-11-21 11:30–11:32): a list that is only for putting items in order never has an ✕ —
  every player row and every settings link always stays; they can only be moved. "Reset order" puts back the
  original order. Helpers and the help guide updated.

## Long names scroll sideways, one way, with a speed per list (requested 2026-11-21 11:34 / 11:35, text helpers)
- Names too long for their row glide continuously right to left, starting over seamlessly (no back-and-forth);
  names that fit stay still. Each row starts at its own moment so a list doesn't move all at once.
- Where: radio station names in every list — favourite stations (radio player, Library → Radio), Find stations
  results (radio player, Library → Radio) and station lists from playlists; Library → Songs (was back-and-forth);
  the phone and network folder lists (folders and songs; were cut to two lines); video names in those lists; the
  players' titles — song, station name and what it plays, video title (under the video and in the sideways bar) —
  now on one line at full size, centred when they fit, gliding when too long (were shrunk onto two lines); Up next
  lists as before.
- A speed slider for each list on its own settings page (0 = still, cut with …; 100 % normal; up to 300 %; Reset):
  Radio → "Favourite stations autoscroll", "Find stations autoscroll"; Library & files → "Songs list autoscroll"
  (starts from the old Songs list setting), "Folder lists autoscroll"; Video → "Video lists autoscroll"; Look →
  "Up next autoscroll" (the old Look slider), "Player titles autoscroll". Text helper on a long press or a still
  hold on the slider.

## Slider helpers once per session (requested 2026-11-21 11:39)
- The helper pop-up that opens when a slider is held still now appears only the first time for each slider; after
  that, holding the slider still does nothing more until the app is restarted. A long press on the setting's text
  still opens its helper whenever wanted.

## Alarms without their permissions: red area and a pop-up (requested 2026-11-21 11:41)
- When Android hasn't given the alarms what they need (usual right after a new install): exact alarms, notifications,
  full-screen alarm (Android 14+), battery not "Restricted" — the alarm area under the clock is red (red tint and
  border, also the small sideways chips) with "⚠ Alarms may not ring: …" on top, whether or not an alarm is on.
- Any touch on the alarms there (an alarm, its switch, + Add alarm, ⚙, ⚠) first shows "Alarms need permissions":
  each missing permission with "Grant", which opens the Android page where exactly that permission is granted for
  the app (11:42): Exact alarms → "Alarms & reminders" for the app; Notifications → Android's own "Allow
  notifications?" request (13+), or the app's notification switch when Android won't ask again / on Android 12;
  Full-screen alarm → "Full-screen notifications" for the app; Battery: Restricted → the app's battery page (or the
  closest page the phone has, last the app's info page). Same "Grant" buttons in Alarm settings → alarm health; "Later" goes on with what was
  touched, Close cancels. Checked again each time you come back to the app: once everything is allowed the area is
  back to normal and touches act at once (a touch waiting behind the pop-up then just goes on).
- Notification symbol (11:44): the same orange rounded square in every state, only the symbol inside changes —
  ▶ playing, ⏸ paused, and ⏭ on the "Up next" notification (was Android's plain play / pause / next arrows). Orange
  in the shade and on the lock screen; in the status bar Android draws every app's symbol white, so there it's the
  same square shape with the symbol cut out.
- Header line of the player notification (11:47 / 11:48): next to the orange ▶ square, what plays and the time
  left — "Pink Floyd · 3 min left" (artist, else album), "RadioBlues-Flac · Live", "Video · 12 min left";
  refreshed once a minute. In the shade and on the lock screen (the status bar can only show the symbol).

## Station bitrate shown as "1500k" (requested 2026-11-21 11:50)
- Radio lists and the radio player show the bitrate as the value followed by k ("1500k", "128k") and no codec name
  any more (no OGG / MP3 / AAC / FLAC …): favourites, Find stations results, Library → Radio, the radio player's
  details line. Other parts of the details (style, country) stay. Station details pop-ups keep the full data.

## One style for every details pop-up (requested 2026-11-21 11:51)
- Songs, videos and stations, from every list and player, now open the same kind of details pop-up (the style of the
  video details): its title, "Tap a line to copy it.", sections in the accent colour, each line with the label on
  the left and the value on the right and thin dividers between lines, a tap on a line copies its value, "Copy all"
  copies everything, Close.
  - Song / video file (list and player menus, was "Tags & ReplayGain"): "Song details" / "File details" — File,
    Tag records, ReplayGain in the file, ReplayGain kept in the app, and which values the player uses.
  - Station (favourites, search results, radio player): "Station details · <name>" — In the app, Live stream (while
    it plays), Radio directory (was label above value, different sizes).
  - Playing video (long press): Video details, unchanged look, now the shared one.
- Share (11:53): every details pop-up also has "Share" — Android's share sheet with all the details as text (to a
  message, mail, notes, another app); next to "Copy all" and "Close".

## The same menu on every media item in Browse and the Libraries (requested 2026-11-21 11:56)
- Songs and videos (phone and network folders, Library → Songs, playlists, queue, Up next): ▶ Play now, Play next,
  Add to queue, Add to playlist…, Details… (was "Tags & ReplayGain…"), Share details…, ReplayGain scan… (songs), and
  what was already there (Select, Move up / down, Open song folder, Remove from lists, Delete file).
- Folders (phone folders in Library, folders in phone / network browsing): now also "▶ Play all now", "Details…"
  and "Share details…" next to Play all next, Add all to queue, Add all to playlist…, New playlist from this folder,
  ReplayGain scan (with subfolders, audio library), Select, Remove folder from lists.
- Folder details (shared style): Folder — name, place, source; Inside (with subfolders) — songs, videos, folders
  with files, total play time (counted when opened). Song / file details now also list artist, album, length and
  the file's place. "Share details…" opens Android's share sheet with the same text as the pop-up.

## Automatic ReplayGain for folders (requested 2026-11-21 12:01–12:03, text helper)
- Settings → Audio → ReplayGain: "Automatic ReplayGain for folders" (on by default).
- When a song without any ReplayGain starts playing from the phone or from the network at home, every song of its
  folder that has none is measured in the background — no question, no notification, no progress, no report.
  Each gets its track gain, kept in the app (files unchanged); the playing song uses its value as soon as it's
  measured. Songs that already have ReplayGain are left as they are. Once per folder while the app runs.
- When the whole folder is done, a short pop-up for 5 seconds: "✓ ReplayGain added — All N songs of "<folder>" were
  scanned", each song with its new gain (+2.31 dB / −4.10 dB, in the accent colour; "not measured" in red), up to
  10 lines then "… and N more". Tap outside to close it sooner.
- Kept as before (12:02 / 12:03): network songs reached through the VPN (away from home) — no automatic scan there
  (slow); setting off: the "No ReplayGain — scan?" question for every folder.

## ReplayGain not written to network files: found, logged, scanned again (requested 2026-11-21 12:06–12:09)
- Every ReplayGain measurement notes, per song, its date and where its values went (written to the file, cached
  until the song ends, kept in the app, not written …).
- Right after writing to a network file, the file is read back from the server; no ReplayGain there → the result
  says "not written: … has no ReplayGain when read back — kept in the app", an error in the ReplayGain log (part of
  "Collect error logs"), and the app keeps the value meanwhile.
- A scan that includes network songs measured less than 10 days ago reads their files first:
  - ReplayGain in the file → left out of this scan, nothing shown;
  - none although the values were meant for the file → it wasn't written: error in the ReplayGain log ("scanned
    for ReplayGain on <date> (<where>), but the file has no ReplayGain — it wasn't written; scanned again"), the
    song is scanned again, and a short pop-up (5 s, at most once an hour, 12:07 / 12:08 no spam) names one such
    song: "⚠ ReplayGain wasn't written — "<song>" — This item was previously scanned for ReplayGain on: <date>".
- Phone songs and the automatic folder scan aren't affected.

## Logs purged monthly (requested 2026-11-21 12:10)
- Every log (servers, ReplayGain, sound engine, player, deleted files, crashes …) drops its entries older than one
  month, oldest first; a log left empty is removed. Done at each app start and once a day while the app runs.
  (The 1000-entries-per-log limit stays.)

## Review of the requests since 1.61.0 (2026-11-21 12:13)
- Long names also glide in the queue (Up next autoscroll) and in playlists and the saved radio songs list (Songs list
  autoscroll) — these had been left cut with "…".

## Video player: "Audio sync" button for Bluetooth (requested 2026-11-21 12:17)
- New "Audio sync" button on the video player (top button row, sideways and upright, and under the video when it's
  on top); it shows the value when one is set ("Audio sync 0.25 s").
- It opens a slim bar at the bottom of the picture: "Audio sync · <sound device>" with the value in a small box
  ("0.00 s"), Reset, ✕, and under it a slider across the whole width for fine setting (12:19): from 0 to 2.00 s in
  0.01 s steps, pushing the sound forward (earlier) — over Bluetooth the sound only ever comes after the picture, so
  it starts at 0 and only goes that way. Applied while dragging; a tap on the picture closes the bar.
- Kept for that sound device and that video file together, and found again whenever that video plays on that
  device. Other videos played on the same device during the same session start from the last value set (a
  Bluetooth device's delay is usually the same), until one gets its own. Older offsets ("this file" / "this output"
  from Audio & subs → Sync sound) are used when there's nothing newer.
- Changing the sound device while a video plays (Bluetooth connects / disconnects) puts on that device's value at once.
- A video that started from the session's value and runs fine with it (12:21) — not changed after 30 s of playing,
  or played to its end before that — keeps that value as its own for that device, like one set by hand.

# After 1.62.0

## Alarm history (requested 2026-11-21 12:30)
- Past alarms are no longer shown in the alarm area in front of the player (12:30 / 12:31): one-time alarms that
  already rang — also those that rang with an older version — and switched-off one-time / "specific dates" alarms
  whose date (or every date) is over.
- They're listed in Alarm settings → new section "History" (under Alarm health), newest first: time, name and when it
  was set for, "Rang <day, time>"; a tap opens the alarm. Each has a switch — on sets it again for its next time
  (a passed date is dropped) and it goes back in front of the player, "06:30 will ring" — and a red ✕ that deletes it.
- Alarms switched off by hand before ringing stay in front of the player as before.

## Open SHOUTcast / Icecast links (requested 2026-11-21 12:34)
- The app is offered in Android's "Open with" for radio links from other apps (browser, mail, chat, file manager):
  - links marked as SHOUTcast / Icecast playlists by their type — audio/x-scpls (.pls), audio/x-mpegurl (.m3u),
    application/xspf+xml (.xspf) and the other names these go by;
  - links marked as live audio streams (audio/mpeg, aac/aacp, ogg, opus, flac) over http / https;
  - links whose name ends in .pls / .m3u / .xspf, even when the site doesn't mark them;
  - icy:// links, and .pls / .m3u / .xspf files opened from the phone.
- A playlist is read and its first stream is played in the radio player, named by the playlist's title
  (else the server name). A bare stream plays directly. Normal song / video files open as before.

## Visualisation button and double tap on the picture (requested 2026-11-21 12:39)
- The visualisation button (round, under the clock button, in the song / radio player) now puts the visualisation in
  the album picture's place (station logo for radio) instead of opening it full screen; ringed in the accent colour
  while on, like the clock button. Pressed again: the picture comes back. Kept while the app runs.
- The clock keeps its priority: pressing the visualisation button while the clock is shown puts the clock away and
  shows the visualisation.
- Double tap on the picture place — album picture, station logo or the visualisation there — opens the
  visualisation full screen. Long press keeps what it did (picture search / Visualisation settings).

## Full-screen visualisation: "Double tap to exit" (requested 2026-11-21 12:42)
- Opening the visualisation full screen shows a short pop-up in the middle, in big letters: "Double tap to exit"
  (dark rounded box, fades away after about 2.5 s). A double tap anywhere closes the full screen, as before.
