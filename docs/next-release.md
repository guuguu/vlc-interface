# Next release: requests kept

## VPN-NAS button (requested 2026-11-19 18:37)
- Same button, same use (tap = switch NAS use on / off, as now); only its label shows the status:
  - "NAS active": NAS in use, reached on the home network
  - "VPN - NAS active": NAS in use, reached through the VPN
  - "VPN active": VPN up, NAS use switched off
  - "NAS unreach": NAS use on, but the NAS can't be reached
  - no VPN and NAS use off: stays "VPN-NAS off" (assumption, unless told otherwise)
