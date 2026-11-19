# Next release: requests kept

## VPN-NAS button (requested 2026-11-19 18:37)
- Same button, same use (tap = switch NAS use on / off, as now); only its label shows the status:
  - "NAS active": NAS in use, reached on the home network
  - "VPN - NAS active": NAS in use, reached through the VPN
  - "VPN active": VPN up, NAS use switched off
  - "NAS unreach": NAS use on, but the NAS can't be reached
  - no VPN and NAS use off: stays "VPN-NAS off" (assumption, unless told otherwise)
- In front of the label: a Wi-Fi or mobile-data symbol for the connection in use, updated live as the phone switches
  (no symbol when there's no connection).

## Settings layout (requested 2026-11-19 18:43)
- Main Settings page becomes a list of links to submenus with clear names; every setting and log page moves into
  one of them (proposed: Sound, Playback & gestures, Look, Library & scanning, Network & NAS, VPN, Saved songs,
  Subtitles, Alarm clock, Visualisation, Setup export / import, Logs).
- Long-press on ANY item of any settings page or subpage: a pop-up explaining that setting.
- All sliders in the settings thin, like the sliders of the player (thin track, small knob).
