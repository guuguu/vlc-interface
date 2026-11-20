# Next release: requests kept

## Sleep button next to the player buttons (requested 2026-11-20 17:38)
- On the song and radio players the Sleep button moves from the extra-buttons row to the right side of the player
  buttons (the column mirroring the clock / visualisation buttons on the left), same menu as now.
- While a sleep timer runs it shows what's left under it (e.g. "25 min" / "2 songs"); both side columns stay equal
  so the play buttons stay centred.

## Radio station search list (requested 2026-11-20 17:40)
- The "Find radio" results list uses the same spacing as the settings pages (row height, gaps and margins follow
  the "Vertical spacing" setting).
- A thin grey separator line between stations.

## Every settings block in its own box (requested 2026-11-20 17:42)
- On every settings page (and the wizard), each category (the group of settings under one section title, e.g.
  Sound, ReplayGain) gets one box for all its items — not one box per item (requested 2026-11-20 17:44): nicely rounded corners, a slightly
  lighter shade of the page's own colour as background and a faint border tinted with the accent colour, so categories
  stand apart; inside a box the items are separated by thin lines; only the section titles sit outside the boxes.
- Separators between the items inside a box stay (requested 2026-11-20 17:45).
- Classification redone on all settings pages (requested 2026-11-20 17:45): headings that sit inside a box and look like
  category titles (e.g. Audio → "Per output device", "Sound engine") become their own categories — a section title
  with its own box — and every item belongs to exactly one category, styled as an item (setting title + text under it).

## Fix: video player's top buttons (reported 2026-11-20 17:48)
- The top bar was squeezed into one row: chips shrank to tiny text, symbols near-invisible, and "Songs" / "Radio"
  overflowed off screen.
- Now two rows, never squeezed: row 1 — back, home, title, lock, settings and the status symbols; row 2 (wraps if
  needed) — output, Audio & subs, Hue & sat, speed, Songs, Radio. Symbols at the button size (at least 75 %), chip
  text at least 13 sp, chips at least 34 dp high.
