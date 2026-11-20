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
