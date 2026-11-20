# Next release: requests kept

## Alarm switches need a 1 s hold (requested 2026-11-20 12:43)
- Switching an alarm on or off (the wide switch on each alarm row, under the clock and in the alarm lists) needs the
  finger held on the switch for 1 second, so a stray touch can't change it.
- While holding, the switch fills up to show the second passing; letting go earlier changes nothing.
- It is a condition, not a question: nothing asks to confirm. A short tap changes nothing and shows the short note
  "Hold 1 s to switch this alarm" (kept: requested 2026-11-20 12:44).
- After the hold, the usual confirmation ("07:30 Mon–Fri will ring / won't ring").

## Clock hides while you use the player (requested 2026-11-20 13:28)
- With the clock shown on the player, scrolling the player hides the clock completely (the page takes its room).
- It stays hidden until 10 s after the last action; any further action (scroll, tap, button) keeps it hidden for
  another 10 s. Then it comes back by itself.
- Look setting "Clock hidden for" (seconds after the last action; default 10).
- Upright only: sideways the clock stays on the left as now. With a text helper pop-up while Helpers are on
  (requested 2026-11-20 13:28).
- The clock doesn't come back while an option or settings window is open (a menu, pop-up, dialog, the output sheet…);
  the countdown starts again only once it's closed (requested 2026-11-20 13:31).
