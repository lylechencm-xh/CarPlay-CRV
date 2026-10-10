# 2021 Honda CR-V Android 4.2.2 CarPlay Test

This branch targets the 2021 Honda CR-V head unit running Android 4.2.2 / API17.

## Current source build under test

- Version: `0.2.28`
- Version code: `47`
- Package: `com.shihab.diplay`
- Commit: use the APK artifact built from the commit being tested
- Primary mode: Auto (USB first, Wi-Fi prepared when supported)

The earlier `v0.2.27-crv-api17` release does not contain the current NCM changes.

Build the APK locally or download an artifact from a successful GitHub Actions run for the current commit. APK files are not
stored in the source repository. Prefer the fixed-signed artifact when it is available; a
debug-signed artifact is suitable for compile/install diagnostics but may not satisfy the Honda
native installer.

## Vehicle observation (2026-10-09)

The local `0.2.28` standalone build connected wired CarPlay on the target CR-V and displayed
navigation. The exported log for that session was empty, so this observation is based on the
vehicle screen rather than a protocol trace. Audio, microphone, touch and long-term stability
remain to be tested separately.

## Automatic USB / Wi-Fi switching (experimental)

The default **Auto: USB first** mode starts wired CarPlay and, when the Honda
firmware permits, prepares a temporary Wi-Fi hotspot and a second AirPlay listener.
The iPhone must receive the Wi-Fi configuration before USB removal. Disconnecting
USB then retires the wired NCM/VPN path while the Wi-Fi listener waits up to 45
seconds for a wireless CarPlay session. Reconnecting USB closes that attempt and
starts a new wired session. If hotspot setup or dual listener binding fails, Auto
continues on USB only; use the session log to check which path was prepared.

Test in this order: establish wired video/audio/touch, verify the log records
`iap2 tx=0x5703`, unplug USB and verify wireless video/audio/touch, then reconnect
USB and verify wired video/audio/touch. Repeat while a call is incoming and after
sending a WeChat voice message. Capture the log and visible status if any step
fails. This source build has not yet been accepted on the target car for automatic
switching; the iPhone may require a fresh session during the transition.

## Standalone experimental Wi-Fi handoff

The home screen also offers **USB connection / Wi-Fi test mode**. Wireless mode first
uses the USB cable for Lockdown/iAP2 pairing and MFi authentication. The app starts
a temporary WPA2 hotspot with a random per-attempt password, sends its details to
the iPhone, and listens for AirPlay on the hotspot address. Leave USB connected
until the screen says **CarPlay active**. Then disconnect USB and check that video,
audio, touch, and reconnect remain usable over Wi-Fi. Use **Disconnect** to stop
the test. The app attempts to restore the vehicle's prior Wi-Fi station and
saved hotspot configuration. Check the car's Wi-Fi settings afterward.

This mode needs the Honda Android 4.2.2 firmware to expose and permit its legacy
hotspot state, configuration, and enable/disable APIs. It refuses to replace an
already active vehicle hotspot and fails if the saved configuration cannot be read
or restored. Firmware may still reject the hotspot request or omit Wi-Fi broadcast
information needed for an iPhone to join automatically. No CR-V hardware acceptance
has been recorded for wireless mode. If the phone does not join, keep the full
diagnostic log, the last visible stage, and the actual iPhone Wi-Fi state; never
include the hotspot password or MFi identity.

## Installation

Update the existing package instead of uninstalling it so saved Lockdown pairings and app-private
MFi provisioning survive:

```sh
adb install -r mobile-debug.apk
```

Confirm that the selected artifact reports package `com.shihab.diplay` and `minSdk=17` before using
it on the head unit.

## MFi identity provisioning

Ordinary source and CI builds contain no accessory identity. A standalone authorized test requires:

```text
offline-mfi/identity.pk8
offline-mfi/certificate.p7b
```

Place both files in the external-files directory printed by the application. The app validates the
pair, installs it into app-private storage and removes the external provisioning copy. Never commit,
upload or attach these files to a diagnostic report.

## Honda OEM and onboard MFi diagnostics

The MFi section of the diagnostic log records whether `link_iap_adapter` is registered, whether its
Binder can be obtained, its descriptor/AIDL candidates, and the unambiguous certificate/signature
methods selected. It also records the app UID/GID/groups and, for each `/dev/i2c-N`, Unix ownership,
mode, adapter/driver metadata, and the result of opening it read-write. The open probe immediately
closes the node and performs no I2C transaction.

To test one known board bus, place a text file named `i2c-node.txt` beside the `offline-mfi`
directory contents and put exactly one path such as `/dev/i2c-1` in it. Only that explicitly selected
node is used for the MFi register self-check; visible buses are never scanned blindly.

## Expected wired progression

```text
iPhone detected
Switching iPhone to CarPlay USB mode
Opening CarPlay USB data paths
USB bulk data path open
Opening CDC-NCM data path
USBMUX ready
iPhone Lockdown ready
iAP2 carkit channel ready
AirPlay listening
Transport pre-auth ready
MFi authentication ready
Starting iAP2 identification/MFi
CarPlay active
```

The implementation first tries the matching Honda kernel CDC-NCM interface. If it is unavailable,
it reports the reason and attempts the userspace NCM fallback.

The connection screen shows four plain-language steps and a recovery hint. Detailed
messages are written to the session log file rather than displayed live on the car screen.
Each segment is limited to 512 KiB; the active session retains its latest two segments.
On startup and rotation, old CR-V logs are pruned to at most eight files and about
4 MiB total. Other files in the directory are left alone.

## What to capture

- Exact APK filename and signing label (`fixed-signed` or `debug-signed`)
- Last status shown on screen
- Current `carplay-crv-v*.log` file and its `-previous.log` segment, if present, from the app external-files directory
- Head-unit Android/build information and iPhone/iOS version
- Whether Trust, CarPlay, USB and VPN prompts appeared
- Whether failure affected connection, video, audio, microphone or touch

Do not include MFi identities, Lockdown records, hotspot passwords or other credentials.

## Hardware acceptance

CI cannot establish successful operation of the Honda USB controller, kernel CDC-NCM network
device, MFi hardware/service, Android 4.2.2 VPN/TUN implementation, decoder, audio route or touch
panel. A release remains a hardware-validation build until the complete wired progression is
confirmed on the target CR-V and iPhone.
