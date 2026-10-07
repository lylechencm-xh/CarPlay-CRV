# Building CarPlay CR-V 0.2.27

## Requirements

- JDK 17
- Android SDK 37 plus Android platform 17
- Android NDK `23.2.8568313`
- CMake `3.22.1`
- Included Gradle wrapper

The runtime baseline is Android 4.2.2 / API17. Do not raise `minSdk` to work around build or lint
errors.

## Source and CI build

Run the same core checks as the CR-V workflow:

```sh
python3 .github/scripts/check-crv-baseline.py
python3 .github/scripts/check-api17-java.py
python3 scripts/check_public_tree.py
./gradlew :shared:testDebugUnitTest :crvlegacy:assembleDebug
./gradlew :crvlegacy:lintDebug :mobile:lintDebug
./gradlew :mobile:assembleDebug
```

Output: `mobile/build/outputs/apk/debug/mobile-debug.apk`.

The ordinary APK contains no MFi identity. It can validate compilation, installation and transport
pre-authentication, but standalone CarPlay authentication requires an authorized runtime identity.

## Standalone car-test build

Set `DIPLAY_AUTH_ASSETS_DIR` to a directory containing exactly:

```text
offline-mfi/identity.pk8
offline-mfi/certificate.p7b
```

Then run:

```sh
./gradlew :mobile:assembleStandaloneDebug
```

The build rejects missing, empty or unexpected credential containers. These files and the Android
signing keystore must never enter Git.

## Signing and publication

The GitHub workflow uses the fixed CR-V test key when its four signing secrets are configured;
otherwise it labels the output `debug-signed`. CI verifies package `com.shihab.diplay`, `minSdk=17`,
the API17 platform surface, DEX035/single-Dex constraints and an Android 4.2.2-compatible v1/JAR
signature.

Installable APKs are distributed only through GitHub Actions artifacts or the
`v0.2.27-crv-api17` GitHub Release. They are deliberately excluded from the source tree.
