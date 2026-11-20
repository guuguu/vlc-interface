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
  - song player → 📻 Radio and 🎬 Video; radio player → ♪ Songs and 🎬 Video; video player → ♪ Songs and 📻 Radio.
- A tap opens that player with what it last played, resuming where it was (songs: last queue and position; radio:
  last station; video: last video and position); what's playing now stops / pauses.
- Nothing played there yet: it opens the matching list (Library songs, radio favourites, videos) instead.
