# 2021 Honda CR-V Android 4.2.2 CarPlay Test

This branch targets the 2021 Honda CR-V head unit running Android 4.2.2 / API17.

## Release under test

- Version: `0.2.27`
- Version code: `46`
- Package: `com.shihab.diplay`
- Git tag: `v0.2.27-crv-api17`
- Primary mode: wired USB CarPlay

Download the APK from the matching GitHub Release or GitHub Actions artifact. APK files are not
stored in the source repository. Prefer the fixed-signed artifact when it is available; a
debug-signed artifact is suitable for compile/install diagnostics but may not satisfy the Honda
native installer.

## Installation

Update the existing package instead of uninstalling it so saved Lockdown pairings and app-private
MFi provisioning survive:

```sh
adb install -r CarPlay-CRV-2021-v0.2.27-api17.apk
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

## What to capture

- Exact APK filename and signing label (`fixed-signed` or `debug-signed`)
- Last status shown on screen
- Complete `carplay-crv-v*.log` file from the app external-files directory
- Head-unit Android/build information and iPhone/iOS version
- Whether Trust, CarPlay, USB and VPN prompts appeared
- Whether failure affected connection, video, audio, microphone or touch

Do not include MFi identities, Lockdown records, hotspot passwords or other credentials.

## Hardware acceptance

CI cannot establish successful operation of the Honda USB controller, kernel CDC-NCM network
device, MFi hardware/service, Android 4.2.2 VPN/TUN implementation, decoder, audio route or touch
panel. A release remains a hardware-validation build until the complete wired progression is
confirmed on the target CR-V and iPhone.
