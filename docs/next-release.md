# Next release: requests kept

## Slider helpers only on a still hold (requested 2026-11-20 17:15)
- Moving a slider never brings up its helper pop-up any more (Player rows spacing, alarm rows, short pop-ups,
  clock / cover hidden for, text size…).
- The helper (while Helpers are on) appears only when the finger stays pressed on the slider without moving
  (about 0.65 s).

## "Both" for paired settings (requested 2026-11-20 17:18)
- Wherever a setting exists for two items, a "Both" choice changes the two at once:
  - Order of the player rows: Songs / Radio / Both (Both: one order, applied to the rows the two players share;
    "Position bar" and "Play next…" keep their place on the song player).
  - Accent colour: Files / Radio / Both.
  - Clock / album picture hiding: the two "Hide … while scrolling" switches and the two "… hidden for" times each get
    a "Both" (one switch / one time for clock and picture).
  - Switch colours: On / Off / Both (same colour for both positions — the knob's side still shows the state).
- From now on, any new setting made for two items gets the same "Both" choice.

## Switch between the players from their header (requested 2026-11-20 17:20)
- Each player's header gets two small buttons for the other two players:
  - song player → "Radio" and "Video"; radio player → "Songs" and "Video"; video player → "Songs" and "Radio" —
    text only, no icons (requested 2026-11-20 17:21).
- A tap opens that player with what it last played, resuming where it was (songs: last queue and position; radio:
  last station; video: last video and position); what's playing now stops / pauses.
- Nothing played there yet: it opens the matching list (Library songs, radio favourites, videos) instead.

## Vertical spacing slider in Settings (requested 2026-11-20 17:23)
- Main Settings page: a second slider, "Vertical spacing", under "Text size": the empty space between and inside the
  setting rows (title ↔ text under it ↔ slider, row ↔ row, section titles) on every settings page, the wizard and
  the help guide — from tight to roomy, default a little tighter than today.
- Helper pop-up while Helpers are on (only on a still hold), previewing two sample setting rows at the chosen spacing.
- Also: every setting row keeps the same left margin (e.g. Audio → "Loudness, Treble and Preamp sliders" is indented
  more than the rows around it today).
- Graphical items on the settings pages (sliders, switches, pills / choice buttons, colour circles, ▲▼ and Reset
  buttons) get less free space around them: the slider's tall touch row, the switch's surrounding gap and the pills'
  outer margins shrink (touch areas stay usable); the "Vertical spacing" slider scales this space too
  (requested 2026-11-20 17:24).
- Every triangle arrow on the settings pages (▶ on links, ▲ ▼ for ordering, ▶ in the wizard / help, ▾ on menus) has
  one size and is clearly visible: the same bright colour, with a disabled arrow (first ▲ / last ▼) still visible
  as a dimmer grey, never near-invisible (requested 2026-11-20 17:24).
- The same for every other symbol on the settings pages (☑ ☐ ✓ ⚙ ⚠ ✕ + ↻ ⓘ, the Wi-Fi / mobile symbol, the colour
  circles…): one consistent size and clearly visible colours, disabled ones dimmer but still easy to see
  (requested 2026-11-20 17:25).
