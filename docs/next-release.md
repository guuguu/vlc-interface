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
- No "on / off" words anywhere for switches, the wizard's "Now:" row included (switch rows show no "Now:" row;
  other settings keep theirs) (requested 2026-11-20 10:55).

## Radio and audio player scrolling (requested 2026-11-20 11:25; audio player and portrait only: 11:27)
- Upright (portrait) only; sideways stays as now. On the radio player and the audio (songs) player, scrolling up moves the cover (album picture / clock) up and out of view.
- The title block then stays frozen under the header (songs: title, artist / album, format line, VPN-NAS button and
  its status line; radio: "RADIO STATION", station name, address, Live, format line,
  the VPN-NAS button and "Live · listening …").
- Scrolling on moves all the rest underneath it (controls, Up next, the other items); scrolling back down
  brings the cover back.
- Sideways (landscape), on both players: the left side (cover or clock, and what's shown with it) is always shown
  whole, sized to fit the screen height, never needing to scroll (requested 2026-11-20 11:28).

## Find media folders, from Settings (requested 2026-11-20 11:30)
- A new item on the main Settings page, "Find media folders": scans the phone's storage (internal, SD card, USB,
  Downloads) for folders holding audio and video, whenever you want.
- Results listed with tick boxes (same light-green ticks); only the ticked ones are added to the Library.
- Folders already in the Library are shown as already added and never overwritten or duplicated.
- The item can be moved like the other links (order chosen on the Look page).
- With a helper pop-up (long-press, and the help pop-up when Helpers are on) explaining what it searches, that only
  ticked folders are added and that existing ones stay as they are (requested 2026-11-20 11:30).

## Helper pop-ups go deeper (requested 2026-11-20 11:32)
- Every helper pop-up (long-press, Helpers) explains more than the line under the setting: what it does, when to
  use it, what changes on and off (or with each value), what it costs (battery, data, space), and the default.
- No pop-up just repeats the line under the setting: settings that today fall back to that line get their own
  longer text.
- New settings: asked each time whether a helper pop-up is wanted.
- Helper pop-ups scroll when the text is long; the OK (close) button stays always visible, in the pop-up's top bar
  next to the title, never scrolling away (requested 2026-11-20 11:33).
