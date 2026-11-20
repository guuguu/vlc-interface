# Next release: requests kept

## Phone / SD card files that fail ("VLC is unable to open the MRL 'fd://…'", reported 2026-11-19 22:11)
- Phone files opened by their real path (/storage/emulated/0/…, /storage/XXXX-XXXX/… on the SD card) when Android
  gives one; the file handle only as fallback; one retry with the other way before the error.
- Card missing / not mounted: "SD card not available" instead of libVLC's text; a folder permission that no longer
  matches the card: ask to pick the folder again.
- libVLC's reason written to Settings → Logs for phone files too.

## Media folders found at first install (requested 2026-11-20 10:51)
- Only on a first install (not on updates): the phone's storage is scanned for folders holding music / videos.
- The usual internal media folders (Music, Movies, Podcasts, Audiobooks, Recordings, DCIM… on internal storage) are
  added to the Library directly.
- Any other folder found (SD card, USB storage, unusual folders) is offered in a list and added only after approval
  (tick / confirm).
- The Downloads folder is added too (offered for approval like the others if it's not a usual media folder).
- Shown on the setup wizard's Library page (requested 2026-11-20 10:53).

## Wizard symbols easy to see (requested 2026-11-20 10:53)
- On every wizard page the tick symbols (☑ ✓) and the small forward arrows (▶, "Grant ▶", "Open ▶", "Add ▶") are drawn
  bigger and in a bright light green, so they stand out from the text.

## Switches (requested 2026-11-20 10:55)
- Everywhere in the app, a switch that is off keeps the same look and colours as when it's on; only the knob's
  position changes (left = off, right = on).
- A switch is greyed only when it can't be used (not available in the current state).
