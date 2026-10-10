# Building CarPlay CR-V 0.2.28

## Requirements

- JDK 25 for the Gradle daemon (`gradle/gradle-daemon-jvm.properties`)
- Android SDK 37 plus Android platform 17
- Android NDK `23.2.8568313`
- CMake `3.22.1`
- Included Gradle wrapper

The wrapper downloads Gradle 9.5.0 from the Huawei Cloud mirror over HTTPS, with
SHA-256 verification enabled. This avoids the official download URL's redirect to
GitHub on networks where those hosts cannot be reached. The download connection
timeout is 60 seconds.

If the mirror is unavailable on your network, change `distributionUrl` in
`gradle/wrapper/gradle-wrapper.properties` to
`https\://services.gradle.org/distributions/gradle-9.5.0-bin.zip`. Keep the existing
`distributionSha256Sum` when switching sources for the same version.

The runtime baseline is Android 4.2.2 / API17. Do not raise `minSdk` to work around build or lint
errors.

The host JDK version does not change this Android baseline. Java source and target
compatibility remain Java 11, and the Android build desugars the application for API17.
On Windows, set the user `JAVA_HOME` to the JDK directory and add its `bin` directory
to the user `Path`. Open a new terminal after changing these environment variables,
then verify with `java -version`, `javac -version`, and `.\gradlew.bat --version`.

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

For the current `0.2.28` source, build locally or use an artifact from a successful matching
GitHub Actions run. The earlier `v0.2.27-crv-api17` release predates these changes. APKs are excluded from the source tree.
