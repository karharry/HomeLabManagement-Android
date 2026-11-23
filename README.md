# HomeLab Management — Android

Monitor and manage a home lab from an Android phone or handheld: **TrueNAS SCALE**, **Linux** (Debian/Ubuntu, Raspberry Pi OS, Arch, **Bazzite / CachyOS**), **Windows**, **macOS** and **OPNsense**, all from one app.

This is the Android version of [HomeLabManagement-Linux](https://github.com/karharry/HomeLabManagement-Linux) (also available for [Mac](https://github.com/karharry/HomeLabManagement-Mac) and [Windows](https://github.com/karharry/HomeLabManagement-Windows)). It talks to your machines directly over your network (or VPN); there is no cloud service and nothing leaves your device except the requests to the machines you add.

> **Status:** version 0.9, a first release for testing. It is a personal tool shared as-is; expect rough edges.

## Features

- **Overview** of every machine, with live CPU, memory, uptime and alert status; a **home-screen widget** shows the same at a glance.
- **TrueNAS**: pools (start/stop scrub), datasets & snapshots, shares, network, apps (start/stop/redeploy, install custom app from Compose YAML), VMs, containers, services, data protection, users, fan control (via the optional `fanctl` container), VPN (containerised WireGuard), updates, console, alerts.
- **Linux / Bazzite / CachyOS**: CPU (per-core), GPU (NVIDIA; AMD/Intel on Bazzite), hardware sensors, storage (incl. ZFS), processes, services, scheduled tasks, logs, updates (apt, pacman, rpm-ostree, Flatpak), users, console, plus auto-detected **Docker**, **Pi-hole** (v5/v6, local DNS), **PiKVM**, **Tailscale**, **Raspberry Pi** telemetry, **libvirt VMs** and **WireGuard / ProtonVPN CLI**.
- **Windows**: CPU, GPU, sensors (LibreHardwareMonitor), processes, services, startup items, scheduled tasks, installed software, updates, event log, users, console, WireGuard.
- **macOS** (as a managed target): power & thermal, startup items, installed software, updates (softwareupdate + Homebrew), logs, users, console, Docker, Tailscale, WireGuard.
- **OPNsense** over its REST API: CPU/memory/storage, interfaces, services, DHCP leases, static DNS/DHCP hosts (Dnsmasq), firmware status.
- **Wake-on-LAN**, **Remote Desktop** hand-off to Microsoft's *Windows App*, VNC links, history charts (7 days), full gamepad/D-pad navigation for handhelds.

## Requirements per machine

The same as the desktop apps: an SSH server with password login for Linux/Windows/macOS machines (and `sudo` for root actions), an API key for TrueNAS and an API key + secret for OPNsense. See the [Linux app's README](https://github.com/karharry/HomeLabManagement-Linux#requirements-and-per-machine-setup) for the per-machine setup (LibreHardwareMonitor, OpenSSH Server on Windows, the `fanctl` container, ...).

## Install

Download `HomeLabManagement-<version>.apk` from the [Releases](https://github.com/karharry/HomeLabManagement-Android/releases) page and open it on the device (allow installs from your browser or file manager when asked). Requires Android 13 or newer.

## Security notes

- Passwords and API keys are stored encrypted with the Android Keystore and are never sent anywhere except to the machine they belong to.
- SSH host keys and self-signed TLS certificates are pinned on first connect (trust on first use) and a later change is refused until you choose *Forget pinned host key*.
- The app allows plain `http://` connections because many LAN devices have no certificate. Prefer HTTPS where you can.

## Building

Requires JDK 21 and the Android SDK (platform 37.2, build-tools 37). Then:

```bash
./gradlew assembleDebug      # debug APK
./gradlew assembleRelease    # release APK (signed if ~/.android-signing/signing.properties exists)
```

## Privacy

See the [privacy policy](https://karharry.github.io/privacy/). The app has no analytics, ads or accounts.

## License

MIT — see [LICENSE](LICENSE).
