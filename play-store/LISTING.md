# Google Play listing — HomeLab Management

Everything to paste into the Play Console. Assets are in this folder.

| Item | Value |
|---|---|
| App name (≤30) | HomeLab Management |
| Package | `com.techloungegr.homelabmanagement` |
| Default language | English (United States) |
| App or game | App |
| Free or paid | Free |
| Category | Tools |
| Contact email | development@techloungegr.com |
| Website | https://github.com/karharry/HomeLabManagement-Android |
| Privacy policy | https://karharry.github.io/privacy/ |

## Short description (≤80)
Monitor and manage TrueNAS, Linux, Windows, macOS and OPNsense machines.

## Full description (≤4000)
HomeLab Management puts your whole home lab in your pocket. Add your machines once and see their status at a glance, then dig into the details and run common actions without opening a laptop.

SUPPORTED MACHINES
• TrueNAS SCALE – pools (start/stop scrub), datasets and snapshots, shares, network, apps (start, stop, redeploy, install from Docker Compose), VMs, containers, services, data protection tasks, users, fan control and VPN
• Linux – Debian/Ubuntu, Raspberry Pi OS, Arch, Bazzite and CachyOS: CPU, GPU and sensors, storage (including ZFS), processes, services, scheduled tasks, logs, updates, users and console, plus automatic detection of Docker, Pi-hole, PiKVM, Tailscale, libvirt VMs and WireGuard
• Windows – CPU, GPU, sensors (LibreHardwareMonitor), processes, services, startup items, scheduled tasks, installed software, event log, users, console and WireGuard
• macOS – power and thermal, startup items, installed software, updates (including Homebrew), logs, users, console, Docker, Tailscale and WireGuard
• OPNsense – CPU, memory and storage, interfaces, services, DHCP leases, static DNS/DHCP hosts and firmware status

EVERYTHING AT A GLANCE
• An overview of all machines with live CPU, memory, uptime and alerts
• Live graphs and a 7-day history for every machine
• A home-screen widget that shows the status of all your machines
• Wake-on-LAN, and one-tap hand-off to Microsoft's Windows App for Remote Desktop

BUILT FOR HANDHELDS AND PHONES
Adaptive layout for phones, tablets and gaming handhelds, with full gamepad and D-pad navigation.

PRIVATE BY DESIGN
• No account, no ads, no analytics and no tracking
• Your machines, credentials and history stay on your device; the app only talks to the machines you add, over your own network or VPN
• Passwords and API keys are encrypted with the Android Keystore
• SSH host keys and self-signed certificates are pinned the first time you connect, and a later change is refused

OPEN SOURCE
HomeLab Management is open source under the MIT License: github.com/karharry/HomeLabManagement-Android

REQUIREMENTS
Each machine needs a little set-up on that machine (an SSH server with password login for Linux, Windows and macOS; an API key for TrueNAS; an API key and secret for OPNsense). The README explains each one.

## What's new (≤500) — 0.9.3
First Play Store release. Monitor and manage TrueNAS, Linux, Windows, macOS and OPNsense machines. Includes reliability fixes from testing on real hardware, a fix for editing port numbers, and the home-screen widget.

## Graphics
| File | Use |
|---|---|
| `icon-512.png` | App icon (512×512) |
| `feature-graphic-1024x500.png` | Feature graphic |
| `screenshots/phone-01_overview.png` … `phone-06_cpu.png` | Phone screenshots (1080×2160, 6 images). The listing uses generic test machines. |

## App content answers
- **App access:** all functionality is available without special access (no login).
- **Ads:** No.
- **Content rating (IARC):** Utility/productivity app. Answer **No** to every question (violence, sexual content, language, controlled substances, gambling, user-generated content, location sharing). Expected rating: Everyone / PEGI 3.
- **Target audience:** 18 and over (not directed at children).
- **News app:** No. **Government app:** No. **Health app:** No. **Financial features:** None.
- **Data safety:**
  - Does the app collect or share any required user data types? **No.**
  - Nothing is sent to the developer or to third parties. Credentials are entered by the user, stored encrypted on the device, and sent only to the machine they belong to.
  - Data deletion: all data is on the device; uninstalling removes it.
- **Permissions:** `INTERNET`, `ACCESS_NETWORK_STATE` (reach your machines), `WAKE_LOCK` and `RECEIVE_BOOT_COMPLETED` (widget refresh via WorkManager). No sensitive permissions. No foreground service is used.
- **Cleartext traffic:** the app allows `http://` because many LAN devices have no certificate. Mention it in review notes if asked.

## Signing (decide before the first upload)
The bundle `app-release.aab` is signed with the upload key `upload-keystore.p12` (SHA-256 `E2:7E:0D:FB:B4:92:34:FD:EA:6B:04:EA:00:41:4D:D6:C1:29:87:68:E1:EA:89:C0:AB:04:C3:88:6D:6F:F2:B9`).

**Option A, recommended: use this key as the app signing key too.** The APKs on GitHub and the Play version then share one signature, so they can update each other.
1. Create the app in the Play Console, open *Test and release → Setup → App signing*, choose *Use the same key as the app signing key* / *Export and upload a key from a Java keystore*, and download the **PEPK tool** and the **encryption public key**.
2. On a computer with Java run:
   `java -jar pepk.jar --keystore=upload-keystore.p12 --alias=upload --output=pepk_out.zip --include-cert --rsa-aes-encryption --encryption-key-path=encryption_public_key.pem`
3. Upload `pepk_out.zip`, then upload the `.aab`.
Keep `upload-keystore.p12` and its password safe (a copy is in the Backups share, folder `Android-Development`).

**Option B: let Google create the app signing key.** Simpler, but the GitHub APK and the Play version are signed differently, so a phone with one cannot update to the other without uninstalling first.

## Testing requirement
Personal developer accounts created after November 2023 must run a **closed test with at least 12 testers for 14 days** before applying for production access. Organization accounts do not have this requirement. Create a closed-testing track, add 12+ testers by email or Google Group, and publish the `.aab` there first.
