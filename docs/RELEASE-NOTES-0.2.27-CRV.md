# CarPlay CR-V 0.2.27 — Android 4.2.2 / API17

This release is the Honda CR-V 2021 compatibility build. It is separate from upstream DiPlay
0.2.12 Android 9 release artifacts.

## Artifact identity

- APK: `CarPlay-CRV-2021-v0.2.27-api17.apk`
- Tag: `v0.2.27-crv-api17`
- Package: `com.shihab.diplay`
- Version code: `46`
- Minimum SDK: `17`
- Target SDK: `28`
- Primary mode: wired USB CarPlay

The APK is published through GitHub Actions or GitHub Releases and is not stored in the source
branch. The release metadata records the exact source commit and whether the artifact used the
fixed CR-V test key or the runner debug key.

## CR-V implementation

- Isolate the upstream shared protocol stack behind an API17 compatibility module.
- Handle iPhone USB enumeration and the detach/re-attach transition into CarPlay USB mode.
- Implement USBMUX, Lockdown pairing persistence, iAP2 identification and MFi authentication.
- Prefer the matching Honda kernel CDC-NCM network interface, with a userspace NCM fallback.
- Bridge the wired IPv6 link through Android VPN/TUN when the userspace NCM path is selected.
- Provide API17-safe H.264 decoding, legacy AudioTrack output, microphone PCM and AirPlay HID touch.
- Record bounded stage diagnostics without raw protocol payloads or credentials.

## Authentication

No MFi identity is committed or bundled by default. Runtime authentication is selected in this
order: verified Honda OEM service, explicitly configured onboard I2C authentication device, then an
explicitly provisioned local identity. The local fallback requires authorized `identity.pk8` and
`certificate.p7b` files.

## CI verification

The CR-V workflow checks:

- Android 4.2.2 / API17 build constants and Java platform surface
- Shared protocol unit tests and CR-V compatibility compilation
- Android Lint `NewApi` errors
- Package name and `minSdk=17`
- DEX035/single-Dex compatibility
- Android 4.2.2-compatible v1/JAR signature
- Absence of APKs, credentials and private-key material from the source tree

## Limitations

- Wired USB is the enabled production test path; wireless handoff remains disabled.
- CI does not prove Honda installer acceptance, USB controller behavior, kernel NCM readiness,
  MFi availability, video/audio hardware compatibility or end-to-end operation with an iPhone.
- A debug-signed artifact may install through ADB but may be rejected or hidden by the Honda native
  installer.
- A source-only build without an authorized MFi identity stops after transport pre-authentication.

Use [the installation guide](INSTALL.md) and [hardware test checklist](../CRV-2021-TESTING.md) for
vehicle validation.
