# Changelog

## 0.9.3
- Editing a port number no longer snaps back to "0" when the field is cleared.
- Removed an unused foreground-service permission (nothing in the app runs a foreground service).
- Launch shortcut applies once, so it cannot pull you back to a page later.
- Google Play assets and listing text added under `play-store/`.

## 0.9.2
Fixes from testing against a real OPNsense 26.7 firewall:
- Services now show their real state (all were shown as stopped).
- Network lists every interface with its addresses and status (it showed one blank card); the System line no longer repeats "OPNsense".
- Uptime, storage and network speed now work.
- Static hosts without a hostname show their IP address.
- Updates page reads "Up to date" with the last check time.
- New read-only API explorer on the OPNsense Console page (type an API path, or `diag`) to help diagnose other firmware versions.

## 0.9.1
Fixes from testing on an AYN Thor against real machines (TrueNAS, Debian, macOS):
- SSH reliability: page loads use their own connection, a failed command no longer drops the shared connection, concurrent commands are capped, and a dead connection is re-established automatically. This fixes "SSH error: open failed / Disconnected" and stuck "Connecting…" on Processes, Services, Logs, VPN, Tailscale, Startup, Software and Users pages.
- TrueNAS: Shares, Network, Users, Updates and Datasets pages now load (and show the real API error if a call fails instead of looking empty); Datasets no longer hangs; History is available.
- macOS: process names show the program name instead of the full path.
- Landscape layout (handhelds, tablets): permanent side menu from 720 dp wide.
- The power estimate runs less often (every 30 s) so it no longer floods the target's sudo log.
- The app handles being re-launched (single instance), so widget taps and shortcuts open the right place.

## 0.9
- First Android release: TrueNAS, Linux (incl. Bazzite/CachyOS), Windows, macOS and OPNsense machines, Wake-on-LAN, Windows App hand-off, home-screen widget.
