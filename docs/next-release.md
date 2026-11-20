# Next release: requests kept

## Fix: album picture comes back empty (reported 2026-11-20 16:23)
- When the album picture came back after the inactive time, its frame was empty: taking it away also dropped the
  loaded picture.
- Fix: while hidden it's kept (no height, not drawn) with its picture still loaded, so it comes back with the picture.
  Same for the clock.
