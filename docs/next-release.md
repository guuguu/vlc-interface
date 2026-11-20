# Next release: requests kept

## Ringing alarm screen sideways (requested 2026-11-20 12:10)
- With the phone sideways (landscape), the "pattern to stop" quest on the ringing screen is partly out of view.
- Everything on the ringing screen must stay visible and flow with the screen: sideways, the items are rearranged
  (e.g. time, alarm name and snooze buttons on the left, the pattern quest and Stop on the right), and sizes shrink to
  fit when needed, so nothing is cut or needs scrolling.

## Alarms list under the clock (requested 2026-11-20 12:12)
- The alarm rows shown under the clock become half as tall as now (less vertical padding and smaller text).
- A Look slider "Alarm rows under the clock" sets their height (half of today's height by default, up to today's).
- With a helper pop-up while Helpers are on, showing a live preview of two alarm rows at the chosen height
  (requested 2026-11-20 12:12).
- The alarms list under the clock on the player shows every alarm (the whole collection, not only the next ones) and
  scrolls on its own within its area when they don't all fit (requested 2026-11-20 12:14).
- The switch of each listed alarm (alarm list and the list under the clock) is drawn wider, horizontally elongated,
  so it's easy to hit; switching it raises a short live pop-up (about 2 s, closes by itself) confirming what was
  done, e.g. "07:30 Mon–Fri will ring" / "07:30 Mon–Fri won't ring" (requested 2026-11-20 12:17).

## How long short pop-ups stay (requested 2026-11-20 12:17)
- A Look setting "Short pop-ups stay for" sets how long every short, self-closing pop-up stays on screen (alarm
  switch confirmations, the 3 s helper pop-ups, saved / added notes…), e.g. 1–10 s; default as today.
- With a text helper pop-up while Helpers are on (requested 2026-11-20 12:17).

## Switch colours (requested 2026-11-20 12:20)
- Every switch in the app gets a background (track) colour for the on position and a different one for the off
  position, so the two states differ by colour as well as by the knob's place (replaces "off looks like on" from 1.37).
- Look settings: "Switch colour · on" and "Switch colour · off", chosen like the accent colours (default: on = accent,
  off = a muted grey-blue). Greyed only when a switch can't be used, as before; still no on / off words.
