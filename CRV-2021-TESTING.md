# 2021 Honda CR-V Android 4.4 Wired CarPlay Test

This branch targets the 2021 Honda CR-V Android head unit on Android 4.4 / API 19.

## Scope

- Wired USB CarPlay only
- Android 4.4 / API 19
- H.264 video via platform MediaCodec
- Audio via legacy AudioTrack
- Touch via AirPlay HID
- USBMUX + Lockdown + iAP2 + USB NCM + IPv6/VPN + AirPlay
- No BYD HUD/CAN integration
- No wireless CarPlay
- No Compose / Media3 / Android Auto dependency

## APK

Install the debug APK built by the `CR-V 2021 Android 4.4 Build` workflow.

Debug application id:

`com.shihab.diplay.crv2021`

Current CR-V test version:

`0.2.11-crv-2021-api19`

## Stable test signing

CR-V test APKs use a fixed test signing identity so later test builds can update the installed app
without changing the Android package signature.

The private test keystore is intentionally **not** committed to this public repository. GitHub Actions
expects these repository secrets:

```
CRV_TEST_KEYSTORE_BASE64
CRV_TEST_STORE_PASSWORD
CRV_TEST_KEY_ALIAS
CRV_TEST_KEY_PASSWORD
```

Expected APK signing certificate SHA-256:

```
E4:E5:E2:47:FF:F3:AF:8E:BE:B1:0C:2A:C4:22:E9:E4:17:25:6A:29:50:A7:74:40:DB:F8:25:09:38:47:B9:FC
```

The workflow fails instead of publishing an APK if the stable signing secrets are missing or the
certificate fingerprint changes.

## First launch

1. Start **CarPlay CR-V**.
2. Approve the Android VPN dialog. The VPN is used only to bridge the iPhone USB-NCM IPv6 link into the local AirPlay server.
3. If the MFi identity is not provisioned, the app will report the exact directory where it expects the files.

## MFi identity provisioning

No accessory private key is stored in this repository or ordinary CI APKs.

Place these two files in the app external-files directory:

```
offline-mfi/identity.pk8
offline-mfi/certificate.p7b
```

Typical KitKat debug-package path:

```
/sdcard/Android/data/com.shihab.diplay.crv2021/files/offline-mfi/
```

The actual path is reported by the app if it differs.

On the next CarPlay start, the app validates the key/certificate pair and copies it into app-private storage.

**Do not commit, upload, or share the accessory private key.**

## iPhone bring-up

Use a direct USB cable first. Avoid hubs during initial testing.

Expected status progression:

```
Connect iPhone by USB
Switching iPhone to CarPlay USB mode
Waiting for iPhone CarPlay USB mode
iPhone attached
Opening CarPlay USB data paths
USBMUX connected
Starting wired CarPlay
MFi identity ready
USBMUX ready
Using saved iPhone pairing
  or: iPhone Lockdown paired and saved
iPhone Lockdown ready
iAP2 carkit channel ready
AirPlay listening on fe80::2:<port>
Starting iAP2 identification/MFi
iap2 identification accepted
iap2 authentication accepted
iap2 power/subscriptions sent
iap2 tx=0x4301 carplay-start-session
CarPlay active
```

On first Lockdown pairing, keep the iPhone unlocked and accept the trust prompt if shown.

## Reconnect behavior

The app distinguishes Apple's intentional USB detach/re-attach during CarPlay mode switching from a real cable unplug.

After a real unplug or wired transport failure, the old controller is released so the next USB connection can start a fresh CarPlay transition without killing the app.

## Field diagnostics

The app records stage/status diagnostics only. Raw MFi keys, certificates, challenges, signatures, Lockdown private keys, and raw protocol payloads are not written by the CR-V field logger.

Current log:

```
/sdcard/Android/data/com.shihab.diplay.crv2021/files/carplay-crv.log
```

Previous rotated log:

```
/sdcard/Android/data/com.shihab.diplay.crv2021/files/carplay-crv.previous.log
```

The exact base directory can vary by ROM/storage mount.

## What to capture from a failed test

Record:

- Last status shown on screen
- `carplay-crv.log`
- Whether the iPhone showed a Trust/CarPlay prompt
- Whether the phone detached/re-attached after "Switching iPhone to CarPlay USB mode"
- Whether audio, video, or touch failed independently

Do not include MFi identity files in bug reports.
