# Install CarPlay CR-V 0.2.27

1. Park the vehicle and keep the iPhone unlocked during the first pairing.
2. Download `CarPlay-CRV-2021-v0.2.27-api17.apk` from the matching GitHub Release or Actions
   artifact. Do not use APK files copied from the source tree.
3. Verify that the artifact is labelled `fixed-signed` when Honda installer recognition or an
   in-place update of the fixed test build is required.
4. Install it using the head unit's supported installer, or from a trusted computer:

   ```sh
   adb install -r CarPlay-CRV-2021-v0.2.27-api17.apk
   ```

5. Open CarPlay CR-V, approve USB access and the local VPN permission, then connect the iPhone to a
   USB data port with a data-capable cable.

The package name is `com.shihab.diplay`; the APK must report `minSdk=17`. Updating in place preserves
saved Lockdown pairing and app-private MFi provisioning. Do not uninstall unless those records are
intentionally being discarded.

## Authentication

Ordinary CI APKs do not bundle MFi credentials. For an authorized standalone test, provision
`identity.pk8` and `certificate.p7b` under the `offline-mfi` external-files directory reported by
the app. The app validates the pair, copies it to private storage and removes the external copy.

Never share credentials in GitHub issues, logs or release assets.

## Current scope

Version 0.2.27 exposes wired USB CarPlay as the production test path. Legacy Wi-Fi handoff code is
present but disabled pending CR-V hardware and credential review.

If connection fails, preserve the complete `carplay-crv-v*.log` session and report the last visible
stage, head-unit build, iPhone/iOS version, cable/USB port and whether Trust, CarPlay, USB and VPN
prompts appeared.

See [the test checklist](../CRV-2021-TESTING.md) and
[0.2.27 release notes](RELEASE-NOTES-0.2.27-CRV.md).
