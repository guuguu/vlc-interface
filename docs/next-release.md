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
- Switch on in History (12:47): the alarm is set for the next time the clock shows its time — today if that time
  is still ahead, otherwise tomorrow — as a one-time alarm (its old date / dates are dropped); a short note says
  "06:30 will ring today" / "… tomorrow".
- Confirmation at the top of the page whenever an alarm is set (12:49): switched on in History, switched on in
  front of the player, or saved on leaving the alarm page (when on). A short pop-up (the short pop-up time, at least
  4 s; tap to close): "⏰ Alarm set · 06:30 · <name>", the day it rings next ("Today, Sat 21 Nov" / "Tomorrow, …" /
  the date; weekly alarms add their days, specific dates how many), and "Sound: Radio · <station>" / "Phone music" /
  "Alarm sound".
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
- Opening the visualisation full screen shows a short pop-up in the middle, in big letters on two rows: "To exit back to application, / double tap anywhere." (12:44)
  (dark rounded box, fades away after about 2.5 s). A double tap anywhere closes the full screen, as before.

## Vibration and lights per alarm (requested 2026-11-21 12:50 / 12:51)
- Each alarm's page, under Volume: "Vibrate (this alarm)" (switch) and "Light blinking (this alarm)" (Off /
  Notification light / Flash light). Kept with the alarm and used instead of the alarm clock settings when it rings.
- Alarm clock settings → Ringing: renamed "Vibrate (default)" and "Light blinking (default)" — used only by alarms
  that have no choice of their own (alarms from before this version, until changed on their page). A new alarm
  starts from the last alarm set, these two included.
- Text helpers on both (hold the setting).

## Screen flash instead of the notification light; flashing stops when the phone moves (requested 2026-11-21 12:55 / 12:57)
- Light blinking choices (alarm clock settings default and each alarm's own): Off / Screen flash / Flash light —
  "Notification light" is replaced by "Screen flash" (alarms and settings that had it get Screen flash).
- Screen flash: while the alarm rings, half of the ringing screen flashes (on / off every 0.5 s, at full brightness)
  — the top half upright, the left half sideways — and the other half keeps the clock, the alarm's name and Snooze /
  Stop / the unlock pattern. Brightness goes back to the phone's own afterwards.
- Colour: a row of swatches under the choice (white, red, orange, yellow, green, cyan, blue, violet), white by
  default (12:57); per alarm like the rest, the settings' colour as default.
- Moving the phone stops the flashing — screen flash or the camera's flash light — at once; sound and vibration go
  on until Snooze / Stop. (The first 1.5 s after ringing starts are ignored so the vibration isn't taken for a move.)
  Rings again after a snooze → flashing again.

# After 1.63.0

## Alarm history moved to the alarm page (reported 2026-11-21 13:41 / 13:43)
- "History" is now at the bottom of the alarm page (new alarm and every existing alarm, under Delete alarm), no
  longer in the alarm clock settings. Always shown; empty, it says "No past alarms yet …". The alarm open on the
  page isn't listed under itself; a tap on a past alarm opens its page in place of the current one.

## Whole alarm system on / off (requested 2026-11-21 14:18; clock kept 14:32)
- Main Settings page, under the settings links: a big button "⏻ Turn off the alarm system" (red) / "⏻ Turn on the
  alarm system" (accent colour), with a line saying what it does.
- Only the alarms are switched off — the clock stays as it is (14:32): the ⏰ clock button, the clock in the players
  with its weather, the clock aspect, screen saver, city and weather settings.
- Turning off asks first, then: a ringing, snoozed or wake-checked alarm ends at once; no alarm is set with Android
  any more (nothing rings, also after a restart of the phone); the "Next alarm" notification and the alarms in the
  phone's calendar are removed. Hidden everywhere: the alarms under the clock (and their red permission warning),
  every alarm setting (Alarm health, Ringing, Waking up, Situations, Alarm page and calendar, System …), Look →
  "Alarm rows under the clock", the wizard's alarm permissions (exact alarms, full-screen notifications, calendar).
- Settings → "Alarm clock" ("Off — the alarm system is switched off") then shows the big "Turn on the alarm system"
  button and the clock's own settings (Display: clock aspect, screen saver; Location: city, weather) — also on the
  wizard's alarm page. The alarms and every alarm setting are kept.
- Turning on: everything comes back, every alarm that was on is set again, and if Android lacks something the alarms
  need (exact alarms, notifications, full-screen alarm, battery) the "Alarms need permissions" pop-up opens with a
  Grant button for each.

# After 1.65.0

## Video player: Browse and Queue buttons (reported 2026-11-21 14:47 / 14:48 "cannot see any playlist or access location to open file")
- New buttons on the video player, with the other chips (sideways and upright over the picture, and under the video
  when it's on top): "📂 Browse" and "☰ Queue N" (N = videos after this one).
- Browse opens the folder (or playlist) the video was played from in the Library, the video marked, with every file
  menu (Play now, Play next, Add to queue, Add to playlist, Details …); from there any other folder or source.
  Back returns to the video player.
- Queue opens a panel over the video (it keeps playing): where it was played from ("Folder: …" / "Playlist: …"),
  Now playing, Up next with each video's length — tap to play it, ✕ to take it out of the queue, long press for its
  menu (Play next, Move up / down, Details …) — "Clear the rest", and "📂 Browse files to add".

## Video player upright: header bar like the song player (requested 2026-11-21 15:30)
- Upright (phone held upright, also with a tall video full screen), a fixed black header bar above the picture, as on
  the song / radio player: first row back, home, lock, video settings, Shazam, background-work sign, screen-on sign,
  exit, spread evenly; second row the other two players (Songs, Radio) on the left and the output on the right.
  Always shown (doesn't hide with the controls); locked, it shows only the lock to unlock.
- These buttons are taken off the picture upright (top symbol row, output, Songs, Radio) and out of the button row
  under the video; the picture starts under the header. Sideways stays as it was (one row over the picture).

## Video player upright: only the position bar on the picture, everything else below it (requested 2026-11-21 15:32)
- Upright, the picture carries only the position bar (time, bar, time left — shows / hides with a tap); every other
  button is under the picture, in groups:
  1. the title;
  2. ⏮  −10  ▶ / ❚❚ (big, accent)  +10  ⏭, centred;
  3. Audio & subs · Audio sync · Hue & sat · speed;
  4. 📂 Browse · ☰ Queue N;
  5. Format: <now> → <next> · Rotate · Bigger / Smaller (tall videos) · 🔄 Turn · PiP;
  then Up next for wide videos. The area scrolls when it doesn't fit.
- Upright is always picture on top, controls below (header bar above): tall videos take about 60 % of the room,
  "Bigger" (or a double tap in the middle of the picture) about 80 %; wide videos keep their own shape. Sideways
  unchanged.

## Video player: Up next like the song player, no Queue button (requested 2026-11-21 15:36)
- Under the upright video's buttons, "Up next" exactly as on the song player: the title with "Queue › <time left>" on
  the right (opens the whole queue page: reorder, remove, save as playlist), then the next files — as many as Look →
  "Up next" count — numbered, names gliding when long, each one's length; tap plays it, long press opens its menu
  (Play next, Move up / down, Remove from queue, Details …). Shown for every video (wide and tall).
- The "☰ Queue" button is removed from the video player (upright and sideways); the files row keeps "📂 Browse".

## Video player rows in "Order of the player rows" (requested 2026-11-21 15:42)
- Settings → Look → "Order of the player rows": Songs / Radio / Video / Both. "Video" lists the rows under the upright
  video's picture — Title · Play buttons · Audio & subs / Audio sync / Hue & sat / speed · Browse · Format / Rotate /
  Turn / PiP · Up next — dragged by ≡ (or a long press) like the song rows, no ✕; "Reset order" puts back the
  original order. Kept in settings and export. "Both" stays songs + radio.
- The video player lays its rows out in that order; the helper's preview shows a Video column next to Songs and Radio.

## Video player button labels (requested 2026-11-21 15:47)
- "Audio & subs" → "Subs/Sync", "Audio sync" → "Audio/Sync" (no value on the button, 15:48 — it's in the bar it opens),
  "Hue & sat" → "Hue/Sat" (both words capitalised, 15:48) — on the video player upright and sideways, and in Look's Video row names.

## Video player: 🔄 turn button removed (requested 2026-11-21 15:49)
- The 🔄 button that turned the phone's screen between upright and sideways is gone (upright, sideways, and from the
  Format row): the phone's own auto-rotate does it. "Rotate" (turns only the film's picture) stays.

## Video player: PiP by double tap in the middle (requested 2026-11-21 15:50)
- The PiP button is removed (upright and sideways). A double tap in the middle third of the picture now opens
  picture-in-picture; double taps on the left / right thirds still seek back / forward. (For tall videos upright the
  middle double tap used to switch Bigger / Smaller — that's now only the "Bigger / Smaller" button.)

## Buttons: no spaces around "/" (requested 2026-11-21 15:53)
- Button labels written with a slash have no spaces around it: Subs/Sync, Audio/Sync, Hue/Sat (video player),
  "Import M3U/PLS" (playlists), "+ LAN/Internet" (Songs list).

## Horizontal rows always fit the screen (requested 2026-11-21 15:56)
- New fitting row: its items are laid out as designed when they fit; when they don't (narrow phone, big Button size or
  text size, long output name), the whole row shrinks evenly — buttons, symbols, text and tap areas together — to
  exactly the screen width, so nothing overlaps and nothing goes past the edge.
- Used for: the song / radio player header (both upright rows and the sideways row), the video player header (both
  rows), the video player's sideways top row (was scrolling sideways), the play row under the upright video. The song
  player's control buttons already shrank this way; the button groups under the video wrap onto more lines.

## Subtitle look: full settings (requested 2026-11-21 16:05)
- Video player → Subs/Sync → "Aa Style" now has, each with a live preview (a picture with the line drawn at its
  height, with border and shadow):
  - Font (as before) and Font size 50–200 % (+ Bold);
  - Font colour (white, yellow, light grey, cyan, green);
  - Font border: colour (black, dark grey, grey, white, dark blue, dark red) and width 0 (none) … 10;
  - Font shadow: colour (same choices), width 0 (none) … 10 (how far it falls), direction (8 arrows ↘ ↓ ↙ ← ↖ ↑ ↗ →)
    and transparency 0–100 %;
  - Dark box behind the text (switch);
  - Position on the picture in 10 steps, 1 = bottom … 10 = near the top.
- Long subtitle lines: libVLC already splits them to the picture's width and keeps every line of the block on the
  picture (the block grows upward from the chosen height).
- "Apply to video" as before (the video reopens at the same spot). Settings saved before keep their look (outline →
  border 4, shadow → shadow 3, dark box → box, old position → nearest step). Export / import carries them.

## Colour codes filtered out of subtitle files (requested 2026-11-21 16:09)
- Every subtitle file the player loads is given to it as a cleaned copy (the original file — on the phone or the
  NAS — is never changed): its colour codes are taken out, so the colours chosen in Subs/Sync → Aa Style apply.
  - SRT / VTT: <font color=…> tags and VTT colour classes; MicroDVD .sub: {c:$…} codes;
  - SSA / ASS: \c, \1c … \4c colour overrides in the lines; the file's styles get the chosen text and border colours
    (black shadow / box).
  - Italic, bold, positions and karaoke timing are kept.
- Covers: subtitles downloaded from OpenSubtitles, files added with "Add subtitle file", shifted / restored copies,
  and the same-name subtitle next to a network (SMB / web) video — now loaded by the app (instead of libVLC finding it
  itself) so it's cleaned too.
- Subtitle tracks inside the video file (MKV / MP4) too (16:12, turned on): libVLC drops their colour codes together
  with all their other formatting (italic, bold). This setting is the player's for every text subtitle (SRT-type), so
  added SRT files show without italic / bold as well; SSA / ASS tracks inside a video keep their own look.

## Choose the subtitles when a video opens (requested 2026-11-21 16:14)
- Opening a video that has subtitle tracks: a pop-up "Subtitles — This video has subtitles. Choose one:" lists Off and
  every track (the one in use marked ●). A tap on a choice sets it (and remembers it for that file) and closes the
  pop-up — no buttons; a tap outside keeps what's set.
- Once per video while the app runs: not again when the same video reopens at the same spot (format, decoder,
  subtitle style changes). Only for videos (not songs or radio).
- The choice is saved silently for that video (16:17): next time it opens — also after restarting the app — that
  subtitle (or Off) is put on by itself and the pop-up doesn't appear. Changing it later in Subs/Sync saves the new one.

## Subtitle format in Settings → Video, with a proportional preview (requested 2026-11-21 16:21)
- The subtitle look moved to Settings → Video → new section "Subtitle format" (above Subtitles): font, font size +
  Bold, font colour, border colour / width, shadow colour / width / direction / transparency, dark box, position in
  10 steps, Reset.
- Preview at the top of the section: a picture shaped like the video playing (16:9 when none; upright videos drawn
  upright), with a two-row sample subtitle drawn in proportion to it as the player draws it — font size, border,
  shadow distance and height on the picture all scaled to the preview.
- Video player: a "Subs/Format" button (with Subs/Sync, Audio/Sync, Hue/Sat — upright under the picture and
  sideways) and "Aa Style" inside Subs/Sync both open Settings → Video scrolled straight to Subtitle format; Back
  returns to the video.
- Saved at once. When you go back to the player, a playing video takes the new format on by itself (it reopens at the
  same spot for a moment) — no "Apply" button any more.

## Every video starts in Fit (requested 2026-11-21 16:25)
- Each video now always starts with the Fit format (whole picture, to the edges), whatever format it had last time.
  The format button changes it while watching; a reopen of the same video at the same spot (subtitle format,
  decoder, format check) keeps the format chosen meanwhile.

## Upright: picture at most 2/3 of the screen, never scrolling (answered 2026-11-21 16:26)
- Upright, the picture is as tall as its Fit shape needs across the full width, but never more than 2/3 of the screen
  height (tall videos: exactly 2/3); it stays in place. Everything else (title, play buttons, button groups, Up next)
  is ordered under it and scrolls in the remaining area.
- The "Bigger / Smaller" button is removed (no longer needed).

## Downloading subtitles: one tap does everything (requested 2026-11-21 16:33)
- Subs/Sync → Download subtitles: tapping a result (the row or its "Use") does the rest by itself — no questions:
  - downloads it and saves it next to the video under a free name: the video's name, else with the language
    ("Movie.es.srt"), else numbered ("Movie.es.2.srt") — an existing subtitle is never overwritten (the "Replace?"
    question is gone); kept in the app when the video's folder can't be written;
  - loads it into the player with the app's subtitle settings (Subtitle format, colour codes filtered), shows it
    and saves it silently as this video's subtitles (used again next time);
  - closes the panel back to the video, with a short note "Subtitles on (ES) · Saved next to the video as …".

## "Subs/Change" button: load / change the video's subtitles (requested 2026-11-21 16:36)
- New button "Subs/Change" on the video player (first in the Subs/Sync row upright, next to Subs/Sync sideways): opens
  the same subtitle pop-up as when a video opens — Off and every subtitle of the video (inside the file, plus any
  downloaded or added), the one in use highlighted (16:37: accent-tinted band with an accent edge, bold, ● and
  "in use"). A tap puts that one on, saves it silently for this video and closes the
  pop-up. With none: it says so and offers only Off.
- The "Subs/Change" button is highlighted in the accent colour (tinted, with an accent edge) while the playing video
  has subtitles of its own (16:38); plain when it has none.

## Buttons under the video: one size, centred rows (requested 2026-11-21 16:41)
- Every button under the upright video (Subs/Change, Subs/Sync, Audio/Sync, Hue/Sat, Subs/Format, speed, Browse,
  format, Rotate) has the same width and height — linked across their rows: the longest label sets the width for all.
- Each group wraps into rows centred across the page (as many per row as fit).
- Sizes follow the Button size setting; when the widest button wouldn't fit across the screen, all of them shrink
  together, text included. Highlighted buttons (Subs/Change with subtitles) keep the accent look.
- The format button now reads "Fit → Fill" (without "Format:") to keep the shared width small.

## Reordering: the button lines only (2026-11-21 16:44 / 16:46)
- Each line of buttons under the upright video is one item in Look → "Order of the player rows" → Video:
  Title · Play buttons · "Subs/Change, Subs/Sync, Audio/Sync" · "Hue/Sat, Subs/Format, speed" · Browse ·
  "Format, Rotate" · Queue box (Up next; named so 16:47) — dragged like the other rows. The buttons inside a line keep their fixed order (the
  per-button lists of 16:43 were taken back out).
- Lines are fixed (at most 3 buttons, never wrapping), centred; all buttons the same size, at most a third of the
  width each — they shrink together, text included, when that's needed. An order saved before keeps its place
  (the old single line becomes the two lines).

## Episodes: the whole series in the queue; save queues as playlists (requested 2026-11-21 16:48)
- Playing a video whose name looks like an episode from a folder (phone or network, subfolders included): the queue is
  the whole succession of that series — every file there with the same series name — in season / episode order
  (never shuffled), starting at the one tapped. Other files of the folder are left out.
  - Recognised: S01E05 / s1e5 / S01.E05, 1x05, Episode 5 / Ep 5 / E05, or a plain number after the name
    ("Show - 05"); the series name is what comes before it (separators, case and years ignored), e.g.
    "The.Office.S02E05.720p" and "the office s2e6" are the same series.
  - Videos that aren't episodes play the folder as before.
- "Save" next to "Up next" (video player and song player): saves the whole queue as a new playlist; the name proposed
  is the series ("The Office") or where it was played from, and can be changed. The Queue page keeps its
  "Save as playlist".

## Queue: drag to reorder (requested 2026-11-21 16:51)
- Queue page and the "Up next" boxes (song player and video player): each entry has a ≡ handle — hold it and drag up
  or down; every half row passed moves it one place in the queue, the row lifted and following the finger. Tap and
  long press (menu) work as before. In Up next an entry can't be moved above the one playing.

## "+ Folder" on a LAN location (requested 2026-11-21 16:57)
- Library → LAN (and Internet): next to a location's folders and "↻ Rescan", a "+ Folder" button. It opens a small
  folder browser of that location from its top: tap a folder to go in, ‹ to go up, "Add this folder" to add the one
  shown — it joins the location's folders (the same as ★ on a folder page) and is indexed for search in the
  background. A folder already added says so.
- For every network location (16:59): each saved LAN and Internet location (and every one added later) has "+ Folder";
  locations "Found on your LAN" have it too, next to "Save as preset" — adding a folder there saves that location
  with the folder (when it needs a user name, Save as preset first). Not for UPnP media servers (no folder paths).

## Video player sideways: top bar that hides itself, buttons in two side columns (requested 2026-11-21 17:03–17:07)
- System buttons on top: back, home, the title, lock, video settings, Shazam, background work, screen-on, exit — on a
  dark band that slides down over the picture when the top edge is touched or swiped down (or the controls are shown
  with a tap) and hides itself 3 s after the last touch; hidden, only a thin handle shows at the top edge. It fits the
  width (shrinks evenly when needed).
- Player buttons in two slim columns against the side edges, all one size, shrinking together when the screen is
  short: left — the output, 📂 Browse, Fit → Fill, Rotate, speed (moved left 17:10); right — Subs/Change (lit when
  the video has subtitles), Subs/Sync, Audio/Sync, Hue/Sat, Subs/Format. Both columns centred on the height.
- Songs and Radio are no longer on the sideways video screen (still in the upright header). The bottom button row is
  gone sideways (its ⏮ ⏭ are in the middle, format and Rotate in the right column): only the position bar at the
  bottom. The middle buttons and the position bar sit between the columns. Double tap in the middle: PiP.

## On screen buttons size; side columns against the edges, buttons one on another (requested 2026-11-21 17:13)
- Sideways: both columns sit against the side edges with the same small padding; separate rounded buttons with a small
  gap (the earlier look kept, 17:15), all the same size; the output is the first button of the left column (its name
  in the accent colour, a tap opens the output choice). Both centred on the height.
- Settings → Video → new section "On-screen buttons": "On screen buttons size" 60–160 % (default 100 %, Reset) — makes
  the video player's buttons bigger or smaller together: the side columns sideways and the buttons under the picture
  upright. Never larger than the screen allows: the columns' buttons share the height equally at most, so they never
  overlap or leave the screen (gaps counted); long labels are cut with "…" when a button gets narrow.
- Position bar sideways (17:18): under the side columns, from edge to edge with 20 dp to each edge (tap or drag
  anywhere on it to jump); the columns end above it and are compressed to the height left.

## Video position bar: time under the finger, double tap "Jump to time" (requested 2026-11-21 17:22)
- Sliding on the video's position bar shows the time at the finger in a small accent bubble above the knob (the time
  on the left follows too); letting go jumps there.
- Double tap on the bar: a "Jump to time" box like a cassette — hh : mm : ss in three boxes (numbers only, 2 digits
  each); a box left empty counts as 0 ("" : 12 : "" = 00:12:00). Jump (or Go on the keyboard) goes there (at most the
  video's end); the time entered is kept and shown again next time. The video's length is shown under it.
- Typed with the app's own small digit pad (17:26) — 1–9, ⌫, 0, → — inside the box; Android's keyboard never opens.
  Tap hh / mm / ss to choose the box (highlighted); two digits fill a box and the next one is chosen; the first digit
  typed in a box replaces what was there; → goes to the next box.
- Every number input in the app behaves the same (17:27): typed with the app's own small digit pad (1–9, ⌫, 0, ✓ or
  →), Android's keyboard never opens — Jump to time (video), Jump to (song player: min : sec), Sleep after N songs /
  minutes, the sleep timer's custom minutes, radio recording "Continue for … minutes", and Port on a network
  location. A tap on a number field opens its pad under it (one at a time), the first digit replaces what was there,
  ✓ closes it.

# After 1.66.0

## 1.66.0 closed at start — fixed (reported 2026-11-21 17:37)
- Cause: the option that was to drop colour codes from subtitle tracks inside videos ("--no-subsdec-formatting")
  doesn't exist in libVLC 3; libVLC refused to start with it, and the app closed as soon as it opened.
- Fixed: the option is removed. Subtitle tracks inside a video (MKV / MP4) keep their own colours and formatting —
  libVLC 3 has no way to drop them; subtitle files (downloaded, added, next to a network video) are still cleaned.
- Safety net: if libVLC ever refuses the engine options again, the app starts anyway with the subtitle look left
  out, and writes why to the Logs — it no longer closes.

## Jump to time cut off sideways (reported 2026-11-21 17:53, screenshot)
- Sideways the box was taller than the screen: the pad's last row (⌫ 0 →) and the Jump / Cancel buttons were cut off,
  and the time sat off-centre. Now its own pop-up laid out for the screen: sideways the title, the time, the length
  and Cancel / Jump on the left and the digit pad on the right; upright one under the other. Keys shrink with the
  room so everything is always on screen. The other digit pads (song Jump to, sleep, minutes, port) shrink their keys
  sideways too.
- Sizes worked out from the screen's size (17:55), upright and sideways, on any resolution: the room left for the pad
  is split into 3 × 4 keys (width 44–96 dp, height 28–64 dp), the keys' text is ~42 % of a key, and the time's digits
  scale with the width (18–34 sp). Sideways the time side takes ~45 % of the width and the pad the rest, full height;
  upright the pad gets the height left under the title, time, length line and buttons. The other digit pads keep
  ~220 dp for their pop-up's title, field and buttons and share the rest the same way.

## Page titles on their own row (requested 2026-11-21 17:57)
- Every page header: the symbols on the first row (back, home, … on the left; the page's own buttons and the gear /
  red ✕ on the right), the page title (and the line under it) on its own row below, full width, up to two lines —
  never squeezed between buttons. All pages with the common header (Settings and its pages, Library pages, folders,
  queue, playlists, alarm pages …), the setup wizard (buttons, then "Setup · N of M" and the page title), the Help
  guide and the Sound output sheet.
- Not changed: the video player full screen (and the players, whose title is in the page itself).
- Library and Playlists pages (17:59, screenshot: "Library" squeezed to one letter per line): their buttons on the
  first row (back, output, home, +, ▶ player, exit, ⋮, gear — fitting the width, shrunk evenly when they don't), the
  big title on its own row under them.
- Title on the first row (18:00), the whole app: every header shows the page title (and its line) on top, full width,
  and the buttons on the row under it — the common header, Library and Playlists, the wizard ("Setup · N of M" and the
  title, then ‹ Help Skip Next), the Help guide and the Sound output sheet.
- Only the title above the buttons (18:03): a page's small line (e.g. "Subtitles, decoding…", a folder's path,
  "Setup · N of M") moves under the button row.
- One fixed title size (18:04): every page title (common header, Library, Playlists, wizard, Help, Sound output) is
  the same size everywhere — 22 dp letters, bold — not changed by the Button size, the settings Text size or a page's
  own scaling (were 17–28 sp and scaled with those settings).
- "Page titles size" (18:05): main Settings page, under Text size — 60–180 % (default 100 % = 22 dp letters, Reset);
  every page title in the app follows it together (still the same size on every page).

## Upright video drawn small in its area (reported 2026-11-21 18:24, screenshot 1.67.0)
- The picture's place under the header was measured from the layout, so the video view started at 0 height; libVLC
  sized the picture for that first tiny view and didn't grow it after (a wide film showed at ~40 % width in the
  middle of a black area).
- Now the place is worked out directly (the video's Fit shape across the full width, at most 2/3 of the screen) so the
  view has its real size from the start, and libVLC is told again (twice, 0.1 s and 0.6 s after) whenever that size
  changes — when the video's shape becomes known or the phone turns.

# After 1.68.0
- **Video picture small at load (upright)**: the picture now gets the right size when a video opens. Before, it was only right after you cycled the format button. Once the picture and the video size are known, the player flips to another format and back, just like cycling the button. The size self-check does the same flip if the picture is still wrong after one re-apply.

## Sound check: two ways (requested 2026-11-21 20:34, "I don't like the music sound on the last 10 versions")
- **How it was checked**
  - Code review of the whole path: libVLC (16-bit) → tone → level → limiter → rate conversion → Android, plus ReplayGain, the scanner and the Loudness make-up gain. Every sound change since 1.55 was compared.
  - Measurement: the app's real sound engine was compiled on its own and fed test signals: a modern-master-like mix peaking at −0.3 dBFS, tones, and a 60 Hz + 7 kHz intermodulation test. Its exact output was analysed.
- **Fine as it was**
  - Neutral settings are bit-perfect: the output is exactly the input.
  - The ReplayGain scanner measures correctly (BS.1770: −20.00 LUFS for the −20.00 reference).
- **Fixed: rate conversion after an output switch**
  - When Bluetooth connects or the output changes mid-song, Android can reopen the output at another rate. The app's simple converter then added mirror tones only 12–21 dB under the treble at 12–16 kHz, a harsh, metallic top. It stayed that way for every following song of the same format.
  - Now there's a proper windowed-sinc converter: mirror tones are ≈ 100 dB under.
- **Fixed: limiter**
  - It lowered the level within a single sample right on each peak, a hard step at every kick drum. It now looks 1.4 ms ahead and glides down before the peak.
  - The harsh "crunch" it added is 14 dB lower.
  - Untouched sound still passes bit for bit.
- **Fixed: albums levelled song by song since 1.62**
  - The automatic folder ReplayGain stored track gain only. ReplayGain "Auto" plays a whole album in order with album gain, so it fell back to track gain: every song was pulled to the same loudness, quiet songs raised and loud ones lowered.
  - The folder scan now measures the folder as one album (track and album gain). Songs it measured before with track gain only are measured again, once, the next time their folder plays.
- **Fixed: libVLC's own ReplayGain switched off per song** whenever the app applies ReplayGain itself, so it can never be applied twice.

## Queue ≡ handles away from the edge, hold to drag (requested 2026-11-21 21:00 / 21:04, layout B)
- Queue page and the Up next boxes (song player and video player): the ≡ moved from the left edge to the right side of the row, just before the length. The order number is now first.
- The ≡ starts a drag only after the finger rests on it for 0.2 s. A swipe that moves before then scrolls the list, so scrolling over the handles no longer moves rows. Then drag as before: one place per half row.

## Search on YouTube for videos (requested 2026-11-21 21:04, old TV series)
- **Long-press menu of every video** ("Search on YouTube"): the video Up next box, the Queue page and the folder / library lists.
- **Long press on the playing video's title** (under the picture upright, in the top bar sideways) opens the Video details pop-up.
- **"YouTube" button in the details pop-ups of videos:** Video details and the file Details.
- **What is searched:** the video's name, tidied up. Dots and underscores become spaces; release tags (720p, x264, WEB-DL, DVDRip …) and [groups] are dropped; an episode code is kept as S02E05 (e.g. "The.Office.S02E05.720p.HDTV.x264-GRP" → "The Office S02E05"). It opens in the YouTube app when installed, else the browser.

## Subtitle / sound operations go straight back to the video (requested 2026-11-21 21:06)
- Done on subtitle Sync or Sync sound (sideways and upright) closes to the video; it used to stop on "Audio & subtitles" first.
- A subtitle file picked with "From file" (now the in-app folder browser, see below) is loaded, put on, and the panel closes.
- Picture colour's back arrow goes to the video (it's opened from Hue/Sat, there's no step before it).
- Kept as steps, for going back and forth (21:09):
  - The back arrow from Download subtitles still goes to Audio & subtitles, and from there to the video.
  - Choosing tracks in Audio & subtitles keeps the panel open, so tracks can be tried one after another. Subs/Change still closes on a pick.

## Settings pages opened from a player go back to the player (requested 2026-11-21 21:08)
- **Which pages:** a settings page opened straight from a player — the video ⚙, Subs/Format and Aa Style, the song / radio ⚙, a long press on the visualisation.
- **What changed:** swiping right or pulling down at the top now goes back to that player. It used to stop on the main Settings page first. Back and the red ✕ already went to the player.
- **Checked, already direct:** the song / radio player's menus and pop-ups (sleep timer, Jump to, speed, song menu, Play next pick) close straight to the player.
- **Left as it is:** the Sound output sheet stays open after a device is picked. It also holds that device's volume, preamp and balance.

## Subtitles found in subfolders and season archives (requested 2026-11-21 21:11 / 21:16)
- When a video opens, its folder and the folders one level under it are checked for subtitles (.srt, .ass, .ssa, .vtt, .sub). This covers subtitles in a subfolder that can't be browsed by hand.
- **Only files that clearly belong to the playing video are offered:**
  - a file named like the video (its name starts with the video's name);
  - a file with the same episode code (S02E05, 2x05), with the same series name or none ("S02E05.srt");
  - any subtitle file in a subfolder named after the video;
  - any subtitle file in a subtitle folder (Subs, Subtitles, Sub, Subtitulos, Sottotitoli, Untertitel, Legendas, Sous-titres, Subtitrari) — only when the video's folder holds just this one video (a film folder).
- **Left out:** in a folder with many videos (a season), bulk files like "2_English.srt" or another episode's file are not offered.
- **Season archives:** a .zip whose name has the video's season ("Show.S02.Subs.zip", "Show Season 2.zip") or this episode ("Show.S02E05.zip") is opened, in the video's folder or one level down. Only the entries for this episode are taken. (Only zip; .rar / .7z can't be opened.)
- **Limits:** at most 12 subfolders, 24 files of 500 KB each, archives up to 20 MB.
- **Where:** network videos (SMB / web) and phone videos.
  - On the phone, libVLC already loads files containing the video's name next to it and in Subs / Subtitles; those aren't added twice.
- **How they're used:**
  - They're copied into the app, colour codes taken out, and added as choices in the subtitle pop-up and Subs/Change.
  - They aren't switched on by themselves; the same-name file next to the video still is.
  - The pop-up waits up to 12 s for subtitles, and subtitles arriving later appear in it while it's open.

## Subtitle file from the movie's folder, browsed in the app (requested 2026-11-21 21:17)
- "From file" (Subs/Sync → Audio & subtitles) and a new "📂 Browse the video's folder…" at the bottom of the Subs/Change pop-up open the app's own browser. Android's file picker is no longer used there.
- **Where it starts:** the movie's own folder (network: SMB / web; phone: the file's folder).
- **Moving around:** a tap on a folder goes in. A clear "⬆ Up one level · <folder>" row at the top goes up from the movie's folder and further (21:20): on the network as far as the share itself (not only the location's saved path), on the phone as far as the top of its storage. Inside an archive the same row reads "⬆ Back to <folder>".
- **What it lists:** folders, subtitle files (.srt .ass .ssa .vtt .sub) and .zip archives. An archive opens to the subtitles inside it.
- **Matching first:** files that belong to the video (its name or episode code) come first after the folders, in the accent colour.
- **Picking a file:** it's loaded (colour codes taken out), put on, and saved silently as this video's subtitles. "Subtitles on: …" shows briefly, and it goes back to the video. Cancel goes back to where it was opened from.
- A phone video without a real file path says the folder can't be read from the app.

# After 1.69.0

## Upright video "dancing" between two formats; picture small upright (reported 2026-11-21 21:39)
- **1.69.0 dancing:** the size check flipped to another format and back while the picture was still wrong, with no limit, so the picture kept jumping between two sizes. That flipping is removed. The check is back to its plain re-apply (at most 6 tries).
- **Real cause of the small picture upright:** libVLC's own sizing assumes the video fills the screen. With the phone upright and the video area wider than tall (a wide film in the upper part), it swaps the area's width and height before fitting the picture. A 1080 × 600 area got a picture made for 600 × 1080, drawn small in the middle. Sideways nothing is swapped, which is why turning the phone fixed it.
- **Fixed:** the video view now tells libVLC an orientation that follows the view's own shape (wider than tall → landscape, taller → portrait). That's the case libVLC handles right, so the picture is sized right upright in every format, from the start.

## Treble: a real treble control (reported 2026-11-21 21:45 / 21:46: "too mild, almost none"; "raise sensibly at least until 12 kHz")
- **Why it was mild:** the old Treble only acted on the top bands, from 8 kHz up and at full strength only near 14–16 kHz, where music has little energy and hearing is least sensitive. The 2–8 kHz range, which is what sounds brighter, wasn't touched.
- **Now (app's own sound output):** Treble is one high-shelf filter in the sound engine, like an amplifier's treble knob, with its corner at 4 kHz.
  - The rise is smooth from about 2 kHz and keeps climbing to 12 kHz, then holds. Mids and bass are untouched.
  - At the slider's end (+5): 1 kHz +0.5 dB, 2 kHz +1.8, 4 kHz +5, 8 kHz +8.4, 12 kHz +9.5, 16 kHz +10 (measured on the engine). −5 is the exact mirror. Each step is 2 dB at the top.
  - It glides when moved, like the other tone controls. Loudness keeps its ten bands.
  - Treble no longer moves the Preamp, since the mids' level doesn't change.
- **libVLC's own output** (fallback) keeps the previous band treble.

## Treble band: parametric settings box (requested 2026-11-21 21:50 / 21:51, mock-up approved, helper yes)
- **Where:** Settings → Audio, new section "Treble band": the one filter the players' Treble slider drives.
  - Double tap the Treble slider in the song / radio player to open it, scrolled there. Back returns to the player. A double tap doesn't change the slider's value, and it also works while the sliders are locked.
- **The curve** is drawn from the values set: solid for Treble +5, dashed for −5, with a dot at the frequency. Dragging on the curve sets the frequency.
- **The settings:**
  - Shelf / Peak: Shelf raises or lowers everything above the frequency, like an amplifier's treble knob. Peak raises or lowers one bell band around it.
  - Frequency: 1000–16000 Hz. Type it with the app's digit pad, slide it (log scale), or drag on the curve.
  - Width: the shelf's slope (0.3–1.0, smaller = gentler) or the peak's Q (0.3–4, smaller = wider band).
  - At slider end (±5): ±4 to ±15 dB.
  - Reset: Shelf, 4000 Hz, slope 0.6, ±10 dB (the 21:45 treble).
- **Scope:** one setting for all songs and stations; each song's own Treble value works on top. It applies at once. Saved with the settings and in export / import.
- **Helper:** hold the section for its text.
- **Output:** app's own sound output only (libVLC's output keeps the plain treble).
- **Flatten at volume** (fourth value, requested 22:03): the treble correction shrinks in proportion to the volume — all of it at volume 0, none at the volume set.
  - Range Off (0) … 200 %, in 10 % steps. For example 150 %: two thirds left at 50 % volume, a third at 100 %.
  - "Volume" is the phone's media volume; on an output Android isn't routing to, it's times the app's own volume (which can go above 100 %).
  - It follows volume changes at once.
  - The box draws a third, white curve: Treble +5 as it is at the volume now. The line under the slider says how much is left ("Now at 60 %: 60 % of the correction").
  - Reset sets it Off.

## Hiss against foobar2000 on the same FLAC: rate conversion (reported 2026-11-21 22:10–22:12, same phone, all controls at 0)
- **Rechecked (22:19):** Android's AAudio opens a stream at exactly the rate asked for (the file's), or fails. So for a normal 44.1 kHz FLAC no conversion happens in libVLC or the app; Android converts, as it does for foobar2000. The case below applies only when the output refuses the file's rate (fallback to 48 kHz), in Bluetooth quality mode (output at the link's rate), or after an output change. It's probably not the cause of the hiss heard; a sound check on the phone is proposed.
- **What happened in those cases:** the app asked libVLC to convert. libVLC never picks its good converters (soxr, speex) by itself; their priority is 0, checked in VLC 3.0's source. On Android it used its old "bandlimited" or "ugly" converter, which adds aliasing in the highs, heard as a fine hiss and a less clear sound. foobar2000 converts with a proper resampler.
- **Fixed:**
  - libVLC now always sends the file's own rate, and the app converts it in its sound engine with the windowed-sinc converter. Measured with a 44.1 kHz file on a 48 kHz output, at 1, 10 and 16 kHz: aliasing and noise ≈ 90 dB under the music, which is the 16-bit floor of the file itself.
  - Anything libVLC still converts (its own output, the fallback) uses soxr, as VLC's own Android app does. The engine still starts if soxr were missing.
- **Applies to everything played (22:32):** songs, video sound and online radio all go through the same sound engine and the same rate setup; libVLC's own output (fallback) uses soxr.
- **Checked and fine:** time-stretch at normal speed passes the sound through untouched (VLC's source); the app's processing at neutral is bit-perfect; the visualisation never touches the sound.

## Sound check (requested 2026-11-21 22:28)
- **Where:** Settings → Logs → "Sound check" → Start, while a song, a video or a radio station plays (22:32). For live radio, decoding isn't compared, since a live stream can't be read twice; the engine and the output are measured. It takes about 15 s and shows a report in the shared details style (copy / share). Text helper on a hold.
- **What it does:** it measures, on the phone itself, what happens to the song playing.
  - It captures 10 s of what libVLC hands the app's sound engine, and of what the engine writes to Android (a capture tap in the engine; nothing is changed).
  - It decodes the same passage of the file a second, independent way, with Android's own decoder (phone, SMB and web files).
- **Decoding (libVLC vs Android):**
  - the file's rate, channels and format;
  - the passage lined up and compared sample by sample: identical, identical except ±1 rounding, or different (how much, how often, level under the music);
  - whether libVLC converted the rate before the engine;
  - whether the file has more than 16 bits (libVLC hands 16-bit to the app's output).
- **App's sound engine:**
  - the digital level left / right (ReplayGain, preamps, balance, app and libVLC volume);
  - whether tone and treble are on, and how often the limiter acted;
  - rate conversion (none, or in the app from → to);
  - out vs in: bit-identical (1.4 ms later), only a level change, or more.
- **Output:**
  - the output and the stream to Android (rate, 32-bit float, AAudio with no audio session);
  - the Bluetooth codec and link rate, Bluetooth quality mode on / off, whether Android converts to the link's rate;
  - the phone volume, and a note on per-app phone sound effects.

# After 1.70.0

## ReplayGain scan didn't write tags to an album (reported 2026-11-21 22:49)
- **Found:** a scan that writes to the files decided "already tagged" from values the app keeps itself, such as the automatic folder scan's (since 1.70 these include album gain). So an album played once, then scanned to write tags, could be passed over.
- **Fixed:** scans that write to the files now check only the files' own tags.
- **The real case (22:51, NAS album, the automatic scan):** the automatic folder ReplayGain kept its values in the app and never wrote the files (as built at 12:01).
- **Now (22:53 / 22:54):** the automatic scan writes ReplayGain per song (track gain) into the files' tags, on the NAS and on the phone.
  - Album gain is left to the manual "ReplayGain scan…" (the user saves album ReplayGain himself). The 1.70 change to album gain is reverted.
  - The song playing gets its tags 1.5 s after the player moves on or stops (a file can't be changed while it plays).
  - Phone files (22:55 / 22:57): written into the music folders the app has write access to, given per folder as a person understands it, not "all files".
    - The first time a phone folder gets ReplayGain, the app asks: "Write ReplayGain into \"Music\"?". It explains that the app gets that music folder and its subfolders only (not mails, photos, messages…).
    - "Choose folder" opens Android's own folder picker on it ("Use this folder" → "Allow"). From then on every song in it gets its tags with no further question.
    - "Not now" isn't asked again (a scan started from a menu asks once more). The automatic scan never waits for the answer; until access is given, those songs keep their values in the app.
    - Settings → Audio → ReplayGain → "Music folders with write access" lists the folders, each with Remove, plus "+ Add a music folder…". Helper text on a hold.
    - A read-only NAS share keeps the values in the app.
- **Used live on the song playing (22:59 / 23:00):**
  - **Why it stopped:** the value was handed to the player only after the whole folder had been measured. With 1.70's album gain, the automatic scan had to finish the whole album over the NAS first (minutes), so the change never showed while the song played.
  - **Now:** the song playing is measured first (and downloaded first from the NAS), and its track value is used the moment it's measured.
  - It doesn't depend on writing the file: written now, after the song, or not at all, the player uses the value at once.
  - Songs the automatic scan measured before, with values only in the app, are measured again once and written.
  - The setting's line and helper say so.

# After 1.71.0

## Treble band box fixes (screenshot 2026-11-21 23:31)
- **Frequency field:** the digit pad opened inside a narrow box beside "Frequency" and was cut off (only two of its three columns: 3, 6, 9 and → missing). The field is now under its label, across the full width, so the pad shows whole.
- **Typing a frequency:** only a value of 1000–16000 Hz was taken, so the first digit was refused and the next ones were added to the old 4000. What you type is now kept as typed and applied as soon as it is a frequency in range.
- **Curve labels:** 100 / 1k / 4k / 12k were cut off at the bottom of the curve. They're measured now and sit fully inside the box; the curves keep above them, and the box is a little taller.

## Log pages: every button checked (reported 2026-11-21 23:38, "share not working, I want a selection")
- **Share (each log page):**
  - It didn't work: the whole log went inside the share message, and a long log is over Android's size limit, so nothing happened. It also ignored "Errors only".
  - Now a selection opens first: "All entries (N)" or "Errors only (M)", with the page's current filter chosen. "Share…" then opens Android's app picker. The log goes as a .txt file ("generated by vlc interface …" first line, time in the file name); short logs also go as the message text.
- **All errors / Indexing pages:** they had no buttons. Now they have Share (Indexing with the same selection; All errors straight to the picker) and Save to NAS.
- **Save to NAS:** it saved, but the window closed at once and its "Saved" line with it, so it looked like nothing happened. Now a short note says "Saved to NAS: <file>". It saves what the page shows (Errors only followed).
- **Empty log:** worked. Its question said "Only this server's log is cleared" on every log; now "Only this log is cleared."
- **Errors only:** worked, unchanged.

## Upright video still drawn small (screenshot 2026-11-21 23:42, 1.71.0 — "fix it final")
- **Why 1.70's fix didn't take effect:** libVLC creates its video surfaces with the layout inflater, and the wrapper handed out the activity's own inflater. So the surfaces were made with the activity's context (portrait), and the shape-based orientation was never seen. The picture was still sized for a swapped area (608 × 342 in a 1080-wide place).
- **Fixed:** the wrapper now gives its own inflater, so every view libVLC makes inside the video view reads the orientation that follows the view's shape.
- **Backstop:** if the picture is still smaller than its format calls for in both directions after two re-applies, the app gives the surfaces that size itself, centred, and their frame the whole video view. Only that "drawn small" case is touched (Fill, Crop and the colour-filter display path are left alone). libVLC re-sizes only when its frame changes, so nothing jumps back and forth.

# After 1.72.0

## 1.72.0 crashed at start (reported 2026-11-21 23:48)
- **The two 1.72 changes that can run as the app opens are removed,** so the start is the same as 1.71's (which started fine):
  - The file-sharing component added to the manifest for log sharing. It checked out correct in the built APK, but it was the only new piece Android loads at start. Logs are now shared by saving the .txt into Download/vlc_interface (like "Collect error logs") and sharing that file; the selection and the app picker stay the same.
  - The layout inflater handed to libVLC's video view. The upright picture size is made right by the size check's backstop instead, which stays.

# After 1.73.0

## The real start-up crash (from the error pack of 2026-11-22 00:53)
- **Trace:** "Unable to create application … lateinit property output has not been initialized" in App.getOutput ← volumeNowPct ← trebleFlatten ← currentToneGains ← applySoundChain.
- **Cause:** "Flatten at volume" (Treble band, since 1.70). Once it was set, the treble calculation asked for the output volume while the app was still starting, before the output router existed, so every start crashed (1.72, 1.73, and 1.70 / 1.71 alike). Going back to 1.71 worked only because the downgrade needed an uninstall, which wiped the settings.
- **Fixed:** the volume is read safely at any point of the start (no output router yet → the phone volume alone), and the flattening can never take the app down (on any error: the whole correction).
- **The 1.73 guesses** (file-sharing component, video view inflater) were not the cause. The inflater for libVLC's video view is put back (upright picture size); logs stay shared from Download/vlc_interface.
- **"Not responding" at 00:46 (same pack), fixed (06:41):**
  - Letting go of the Hue/Sat slider reopens the video at the same spot to apply the colour. The old video was closed on the screen's thread: libVLC's setMedia waits for it, and a network video stuck mid-read kept that thread waiting over 10 s, so Android reported the app as not responding.
  - Now every reopen at the same spot (picture colour, decoder, subtitle format, picture check) first closes the old video on its own thread. The reopen follows when that's done, with nothing left to wait for, and the screen stays responsive meanwhile.
  - A newer reopen, or another video chosen meanwhile, drops the pending one. The stop isn't taken for the end of the video.

## Clock: time zone and temperature now > in 6 hours under the city (requested 2026-11-22 06:44 / 06:47)
- **On the clock face:** the line under the city's short name changes every 5 s between the time zone (UTC+1) and the temperature at the city now → 6 hours later, with the user's own ">" sign (15°>18°, no letters).
  - The block keeps the width of the longer text, so it doesn't move.
  - Until the temperatures are known, it shows the time zone.
- **Settings → Alarm clock → Location → "Under the city":** Both (default, alternating) / Time zone / Temperatures. Text helper.
- **Temperatures:** the current temperature and the hourly forecast 6 hours ahead (the city's own hours), from the same weather service as the icon, refreshed with it. Weather kept from before is fetched again once to get them.

# After 1.74.0

- **"Not responding" when picking a subtitle while the video was still opening (07:01):** the tap waited on libVLC, which was busy opening the file from the network. Subtitle and audio track picks, subtitle files added, the kept per-video tracks put back, and the subtitle/audio sync offsets now go to libVLC from their own thread. The choice shows at once; the screen never waits.

## Small notice when the app crashed (requested 2026-11-22 07:08)
- A one-line notification: "vlc interface crashed · check error log" (or "… stopped responding · check error log"). It's silent: no sound, vibration or pop-up, just the small icon and the line in the notification shade.
- **App crashes:** posted the moment the crash happens.
- **Crashes in the sound / video code and "not responding":** the app can't catch these, so the notice is posted at the next start, from Android's record of why the app ended.
- A tap opens Settings → Logs (where "Collect error logs" is). Always the same single notice, replaced, never piled up.
- Its own channel, "App problems", in the app's notification settings.

## Search in Settings (requested 2026-11-22 07:19, mock-up shown)
- **Where:** a "Search settings" bar at the top of the main Settings page (the app's usual search bar: count and ✕).
- **What it finds:** every settings page (the main page, Audio, Playback & gestures, Video, Radio, Look, Library & files, Network & NAS, VPN, Alarm clock, Clock aspect, Visualisation, Export / import, Logs, Find media folders), their sections and each setting by its name, its grey line, its page and section, or words of its explanation. Every typed word must match; accents and capitals don't matter.
- **Results:** the setting's name with the typed words in the accent colour, "Page › Section" under it, its grey line; "Found in its explanation" when only the explanation matched. Best matches first (name starts with the word, then name, then page / section, then explanation).
- **Every result is a link (07:20):** a tap opens its page scrolled to that setting, lit in the accent colour for 2 s. Settings that are only a title line open at their section, lit the same way.
  - Only what a page can scroll to is offered. Parts shown only at times are left out (Alarm health, found folders, dialogs). With the alarm system off, only the clock's settings of the Alarm clock page are offered.
  - Should a setting still not be on its page when opened (hidden by another setting), the page opens at its section, or says it isn't shown right now and drops it from the results for this session.
- **Back** from a result returns to the search with the same words and results. ✕ clears it.
- The players' shortcuts (Subs/Format → Subtitle format, double tap on Treble → Treble band) use the same way of opening a page at a place.
- **Kept up to date:** the list of settings is rebuilt from the settings pages at every build.
- **Search history (07:26):** under the search bar, the last 6 searches as small buttons, newest first, kept across app restarts. A search is added when one of its results is opened. A tap on a button searches it again (the button is lit while shown); its ✕ removes it from the history.

## Clock temperature "now" measured, not modelled (reported 2026-11-22 07:28, 2° off AccuWeather for Málaga)
- **Why it differed:** the temperature now came from Open-Meteo's forecast models, a calculated value for a grid square around the city. AccuWeather corrects its value with real station readings (for Málaga, the airport).
- **Now:** the temperature now is the one measured at the nearest airport weather station within 35 km (its official half-hourly report, METAR, from aviationweather.gov; free, no account), if reported in the last 2 hours. No station near the city: the forecast's value, as before.
- The temperature in 6 hours and the weather icon stay from Open-Meteo's forecast.
- Weather refreshed every 15 min (was 30) so the measured value stays current. Helpers updated.

## Interrupted indexing kept and resumed (requested 2026-11-22 07:36)
- **Before:** indexing a NAS folder that stopped part-way (background work paused, VPN on, battery 15 % or less, the NAS or network gone, the app closed) kept nothing of that run. It started again from the top, and after a stop for low battery or the app closing it didn't start again at all.
- **Now:** every folder read during a run is kept as it goes (saved every 10 s and when it stops), across app restarts.
  - The songs gathered so far are found by searches at once. A folder indexed before keeps its whole old index for searches until the new run ends.
  - A subfolder that can't be read while the server has stopped answering ends the run (kept, to resume). One that can't be read while the server answers is left out, as before.
- **Resumed by itself, no question:** at app start, when background work is resumed, when the VPN goes off, and every 5 minutes while one is waiting. That covers the battery back above 15 % and the NAS answering again. A server that doesn't answer is skipped quietly until the next check.
  - Only the folders not read yet are read; the Indexing log says "resumed at N songs (M folders already done)".
- **Started by hand** (🔍 Index on the folder, ↻ Rescan, + Folder, pinning a folder) on a folder whose indexing was interrupted: a question, "Resume indexing?", with when it stopped and how many songs are kept. **Resume** reads only what's left; **Start over** reads everything again (tags come from the cache). Tap outside: nothing is started. A folder being indexed right now isn't asked about.
- The folder's button shows "🔍 Resume" while an interrupted run is waiting.
- The Indexing log notes each stop with the songs kept ("stopped at N songs — kept, it resumes from there").

## ReplayGain: write access asked while playing (requested 2026-11-22 07:40)
- Besides the first scan (a scan from a menu waits for the answer; the automatic scan asks without waiting), the write-access question now also comes when a phone song starts playing and its music folder has no write access yet, so ReplayGain from that listening session can go into the files' tags.
- Once per music folder while the app runs; the song plays on meanwhile. "Not now" isn't asked again until the app restarts (a scan from a menu still asks once more). A folder already granted is never asked.
- Only while "Automatic ReplayGain for folders" is on (that's what writes ReplayGain during playback).
- **"No" is an answer (07:41):** the question's "Not now" is now "No", and it's kept for good: that folder is never asked again, by scans (also from a menu) or while playing, also after restarting the app. Access can still be given by hand under Settings → Audio → ReplayGain → "Music folders with write access" → "+ Add a music folder…", which clears the "No". Backing out of Android's folder picker without choosing only skips the folder until the app restarts. The question's text and the setting's helper say so.
- **Buttons: Yes · No · Choose location (07:43).** Yes: Android's folder picker opens on the suggested music folder. Choose location: the picker opens at the top of the phone's storage, to pick another folder (e.g. one holding all the music). No: kept for good, as above. On a narrow screen the buttons wrap onto two lines.
- **Choose location (07:44 / 07:45):** opens the app's own chooser instead of Android's picker at the storage top:
  - **The list:** the phone's music folders not yet approved for writing (07:45: approved ones and folders inside them are left out), with how many songs each holds (subfolders included). It shows the top folders (Music, Download/…, the SD card's) and the folders around the song asked about, the suggested one in the accent colour.
  - **Browse the phone's music folders…:** the app's own browser, only through folders that hold music, with song counts; "⬆ Up one level" / "Back to the list"; "✓ Use this folder (N songs)" on the folder shown.
  - Picking a folder opens Android's own picker right on it, where it's approved ("Use this folder", "Allow"); Android grants write access only through that step. Back returns to the question.
  - Storage roots and the Download folder itself aren't offered (Android's picker refuses them); folders inside them are.

## "Both" → "All" on every choice (requested 2026-11-22 07:46 / 07:47)
- **Found:** Look → "Order of the player rows" showed Songs / Radio / Video / Both, where "Both" still meant only songs + radio from before the Video choice was added.
- **Now "All" everywhere a choice covers every option, even with two (07:47):** Look → order of the player rows (Songs / Radio / Video / All), hiding while scrolling (Clock / Picture / All), switch colour (On / Off / All), accent colour (Files / Radio / All); Visualisation → instead of album pictures (Off / Songs / Radio / All); Alarm clock → Under the city (All / Time zone / Temperatures); the Songs list import question (All / Playlist / Add folders to library). Helpers and grey lines say "All".
- **Order of the player rows → All now includes Video:** the song order goes to the radio player as before, and the video player's matching rows (Title, Play buttons, Up next) take the same order among themselves; its other rows (Subs, Hue/Sat…, Browse, Format) keep their places. A line under the choice says so. Reset order resets all three.
- The saved choices keep working (only the names changed).

## Video player's Browse: every place, every level, subtitles too (requested 2026-11-22 08:02 / 08:03)
- **📂 Browse** (video player, upright and sideways, and "Browse files to add" in the queue) opens the playing video's own folder — on the phone, the NAS or an Internet location — showing videos and subtitle files (and its folders), whatever the Libraries' Audio / Video switch is on.
- **Up one level** at the top of every folder page: on the phone as far as the top of the storage (Phone / SD card), on a network location past its saved path up to the server itself (its shares) or the site's top.
- **Places** under it, a sideways row: 📱 Phone, 💾 SD card (when there is one) and every saved LAN 🖧 and Internet 🌐 location. Locations are checked when the page opens: dim while checking, hidden when they don't answer (VPN routing followed).
- **Phone folders now list their subfolders** (any folder holding songs or videos), so the phone can be walked down and up like the NAS.
- **Videos:** tap plays; a long press opens the usual file menu (Play now, Play next, Add to queue, Add to playlist, Details, Search on YouTube…). Folders: the folder menu (Play all, Add all to queue / playlist, Details…).
- **Subtitle files** (.srt .ass .ssa .vtt .sub, with a CC mark): a tap loads it into the video playing (colour codes taken out), saves it silently as this video's subtitles, and goes straight back to the video ("Subtitles on: …"). Phone folders list the subtitle files Android lets the app see.
- The same Up and Places rows are on every folder page of the Library (phone and network), without the subtitle files there.
- **Season archives (08:06):** zips named for a season (Show.S02.Subs.zip, "Season 2.zip", Temporada / Staffel / Saison / Stagione 2…) are listed too, marked ZIP with "Season N subtitles archive"; the playing video's season first. A tap opens it: its subtitles in a list, this episode's first in the accent colour; a tap on one puts it on the video, saves it for this video and goes back to the video. Archives up to 20 MB.

## Indexing automatic for the home network; ReplayGain goes first (requested 2026-11-22 08:09)
- **Automatic:** every pinned folder of a home-network (LAN) location that isn't indexed yet, or whose indexing was interrupted, is indexed by itself as soon as everything allows it: the server answers, background work isn't paused, the VPN is off, the battery is above 15 %, and no ReplayGain scan is running. Checked at app start, when background work is resumed, when the VPN goes off, when a ReplayGain scan ends, and every 5 minutes.
- **Phone songs** need no indexing: Android keeps their list, always searchable.
- **Internet locations** are indexed when asked (pin, 🔍 Index, ↻ Rescan), so mobile data isn't used by itself; their interrupted runs still resume by themselves.
- **ReplayGain has priority:** when a ReplayGain scan starts or is waiting (from a menu, the automatic folder scan, the player), the indexing running pauses at once — what it gathered is kept — and indexing asked meanwhile waits. 5 s after the last scan is done (nothing more queued), indexing resumes by itself from where it stopped. Both are noted in the Indexing log ("Indexing paused: ReplayGain scan running" / "Indexing resumed: ReplayGain scan done").
- **Index button label (08:11):** on the LAN (and Internet) locations' folder rows the button always reads "🔍 Index" (no more "Indexed" / "Resume"); its colour shows the state — accent: indexed (or an interrupted run kept), grey: not indexed yet; a spinner while indexing.

## Video files indexed too (requested 2026-11-22 08:13)
- Indexing of pinned network folders now also keeps the video files (.mkv, .mp4, .avi …), with the very same logic as the songs: same pins and 🔍 Index buttons, automatic for the home network, interrupted runs kept and resumed (asked when started by hand), paused for ReplayGain and resumed after. Videos need no tag reading, so they add little time.
- The song searches (Songs list, Jump to, Play next) stay songs only; the index keeps the videos apart for video searches. The Indexing log says "Indexed <folder>: N songs, M videos"; progress and the resume question count files.
- **Videos searchable, in two places (answered 08:13: Both):**
  - **Library → Videos:** with the Audio / Video switch on Video, the Songs tab becomes "Videos" (the switch now shows on that tab too): every video on the phone (the Library's places) and in the indexed network folders whose server answers now, A–Z, with a search (title, series or file name, 2 letters or more). Each row: name, its folder, Phone or the location's name, length. Tap plays it; long press opens the usual file menu (Play next, Add to queue, Add to playlist, Details, Search on YouTube…).
  - **Video player → "🔎 Find"** (next to 📂 Browse, upright and sideways): the "Find a video" search — the queue's videos and the same videos as above. A tap plays it (from its place when already in the queue, else right after the current video); long press: its menu.

## Indexing settings (requested 2026-11-22 08:19)
- **Settings → Library & files → new section "Indexing" → "Index again":** Never / Every N days (default 4; days typed with the app's digit pad, 1–365, Reset) / When a new file appears. A line under it says what the choice does. Saved with the settings and in export / import; found by the Settings search; long press for its helper.
  - **Never:** a pinned folder is indexed the first time, then only when you tap 🔍 Index or ↻ Rescan.
  - **Every N days:** each pinned home-network folder is indexed again by itself N days after its last indexing (checked with the other automatic indexing; folders indexed before this version count from now).
  - **When a new file appears:** when a network folder is opened in the app and holds a song or video that isn't in the index, that folder (with its subfolders) is indexed again and merged into its pin's index.
- **A file played that isn't indexed yet:** its folder (with subfolders) is indexed at once in the background, whatever the setting — merged into the pinned folder above it, or kept on its own when it isn't under a pin, so it's found by searches afterwards. Once per folder while the app runs.
- **Transparent:** indexing runs on its own pool of 6 background threads at low priority (below the player and the screen), several folders and files at once; it still waits for paused background work, the VPN, low battery and ReplayGain scans (the folder asked for is done right after).

## File associations (requested 2026-11-22 08:24 / 08:25, helpers yes)
- **Settings → new page "File associations"** (in the settings links, its order set in Look), in the app's usual format (sections, switches with the grey line, long-press explanations, helpers on a change when Helpers are on). Web links (http / https) aren't managed here and keep working as before.
- **Types, each with its own switch (registered = Android offers the app for it):**
  - Audio files: MP3 · FLAC · AAC / M4A / ALAC · Ogg / Opus · WAV / AIFF · WMA · APE / WavPack / DSD · Matroska audio · MIDI / AMR / other.
  - Video files: MP4 / MOV · MKV · AVI · WebM · TS / M2TS · MPEG / VOB · WMV / ASF · FLV · 3GP · Ogg video.
  - Radio playlists: PLS · M3U / M3U8 · XSPF (files opened from other apps).
  - Radio links: icy:// · mms:// / mmsh:// · rtsp:// · rtmp:// (new: mms / rtsp / rtmp links open in the radio player).
  - Each row's line says who opens the type now: "Main app ✓", "Android asks which app each time", "main app now: <app>", or "Not offered".
- **"Make it the main app for …"** under each registered type that isn't the main one: Android doesn't let apps make themselves the main app, so the steps it allows are shown and checked:
  - another app is the main one → "Open <app>'s settings" (→ Open by default → Clear defaults), then
  - "Open a … file / link": a real file of that type from the phone (or a test link) opens through Android's list — choose vlc interface → "Always". The app only confirms ("vlc interface opens MP3 now (main app ✓)"), plays nothing, and returns to the page, which shows the new state.
- **All types:** Register for every type · Deregister every type · Stop being the main app (every type opened by default goes back to Android's question; types stay registered).
- Technically: each type is its own entry of the app (an activity alias with that type's filter) switched on / off with Android's package manager; the old catch-all audio / video filter is replaced by these entries (all on by default, as before).
- Helpers: the page, every type, and each action (register / deregister one or all, stop being the main app).
- **Any audio / video kept for compatibility (08:28):** two more switches, "Any other audio" (last of Audio files) and "Any other video" (last of Video files), on by default — the app is still offered for every audio / video type, also rare ones not listed. While one is on, a listed type of its kind switched off says "Switched off, but still offered through \"Any other …\""; switch both off to take the app away from that type completely. Helpers for both.
- **On uninstall (08:30):** nothing is left behind — Android itself removes every association with the app's package (the per-type entries, their on / off state, and every "Always" choice for the app), and those types go back to the other apps or to Android's question. An update keeps them as set. Said in the page's helper.

## Export / import brought up to date (requested 2026-11-22 08:32)
- **Checked:** every setting (115) and every alarm / clock value was already in the setup file. What was missing were the things kept outside it.
- **Now exported and imported, each with its category:**
  - Settings: the choices left on the pages (Songs / Radio / All …), the Settings search history, the folders answered "No" for ReplayGain write access, the album pictures you rejected (plus every new setting: treble band, indexing, "Under the city", …).
  - VPN: the extra subnets and the servers ignored for the VPN.
  - Radio favourites: also the songs saved from the radio.
  - Resume positions: also what each player played last.
  - **New category "Per-video choices"**: per video file — format, decoder, deinterlacing, audio / subtitle tracks, colours, audio sync per sound device.
  - **New category "ReplayGain values kept in the app"**: the values measured but not written into files, and when each network song was measured.
  - **New category "File associations"**: which types are switched on (being the main app stays Android's choice on each phone).
- Replace: those parts are replaced; merge: mine kept, new entries added. An older setup file imports as before; whatever it doesn't have keeps this phone's current value (new settings stay at their current / default values).
- Parts the app reads when it starts: after the import a question offers "Restart now" (or Later: they apply next time).
- Setup files are now format 2.
- **Never forgotten again:** the build checks that every place the app stores data is classified (exported with a category, or this phone only: caches, logs, indexes, battery / crash notes, weather), and fails if something new isn't — so export / import always follows the app.
