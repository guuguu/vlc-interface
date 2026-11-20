# Next release: requests kept

## Clock / album cover scroll away smoothly (requested 2026-11-20 15:57)
- Upright: when you start scrolling, the clock or the album picture at the top of the player scrolls up normally with
  the page, until it's out of view; only then is it taken away, without the page jumping (the page keeps its place).
- It then stays away as now: back after "Clock hidden for" / "Cover hidden for" seconds from the last action, never
  while a menu, pop-up or settings window is open.
- The clock is no longer pinned above the page upright: it's the top of the page and scrolls with it.
- Look switches "Hide the clock while scrolling" and "Hide the album picture while scrolling" (both on by default;
  text helper pop-ups while Helpers are on) (requested 2026-11-20 16:02):
  - On: as above — scrolls away with the page, taken away once out of view, back after its "… hidden for" time.
  - Off: the clock is pinned above the page and always stays; the album picture just scrolls with the page and is
    never taken away.
