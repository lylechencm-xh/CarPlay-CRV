#!/usr/bin/env python3
from pathlib import Path
import sys

ROOTS = [Path("shared/src/main/java"), Path("mobile/src/main/java")]

# Complete upstream DiPlay remains intact. API17 validation follows the exact CR-V legacy
# compilation surface defined in crvlegacy/build.gradle.
EXCLUDED_PREFIXES = (
    "shared/src/main/java/com/shilapi/xcertplay/adb/",
    "shared/src/main/java/com/shilapi/xcertplay/hud/",
    "shared/src/main/java/com/shilapi/xcertplay/glance/",
    "shared/src/main/java/com/shilapi/xcertplay/location/",
)
EXCLUDED_FILES = {
    "shared/src/main/java/com/shilapi/xcertplay/network/CarHotspotAdbFallback.kt",
    "shared/src/main/java/com/shilapi/xcertplay/network/CarHotspotSettings.kt",
    "shared/src/main/java/com/shilapi/xcertplay/network/CarHotspotStatus.kt",
    "shared/src/main/java/com/shilapi/xcertplay/network/CarHotspotTethering.kt",
    "shared/src/main/java/com/shilapi/xcertplay/network/CarPlayBonjour.kt",
    "shared/src/main/java/com/shilapi/xcertplay/network/ExistingWifiManager.kt",
    "shared/src/main/java/com/shilapi/xcertplay/network/HotspotInterfaceBssid.kt",
    "shared/src/main/java/com/shilapi/xcertplay/network/HotspotJoinCapability.kt",
    "shared/src/main/java/com/shilapi/xcertplay/network/HotspotJoinElement.kt",
    "shared/src/main/java/com/shilapi/xcertplay/network/HotspotJoinFirmware.kt",
    "shared/src/main/java/com/shilapi/xcertplay/network/HotspotJoinJournal.kt",
    "shared/src/main/java/com/shilapi/xcertplay/network/HotspotJoinLock.kt",
    "shared/src/main/java/com/shilapi/xcertplay/network/HotspotJoinPlatform.kt",
    "shared/src/main/java/com/shilapi/xcertplay/network/HotspotJoinRepair.kt",
    "shared/src/main/java/com/shilapi/xcertplay/network/HotspotJoinRepairMain.kt",
    "shared/src/main/java/com/shilapi/xcertplay/network/HotspotJoinTransaction.kt",
    "shared/src/main/java/com/shilapi/xcertplay/network/LegacyHotspotRadio.kt",
    "shared/src/main/java/com/shilapi/xcertplay/network/LocalOnlyHotspotInterfacePolicy.kt",
    "shared/src/main/java/com/shilapi/xcertplay/network/LocalOnlyHotspotManager.kt",
    "shared/src/main/java/com/shilapi/xcertplay/network/LocalOnlyHotspotRadioInfo.kt",
    "shared/src/main/java/com/shilapi/xcertplay/network/ManualHotspotInterfaces.kt",
    "shared/src/main/java/com/shilapi/xcertplay/network/ManualHotspotManager.kt",
    "shared/src/main/java/com/shilapi/xcertplay/network/ManualHotspotReadiness.kt",
    "shared/src/main/java/com/shilapi/xcertplay/network/P2pConfigBuildDiagnostics.kt",
    "shared/src/main/java/com/shilapi/xcertplay/network/P2pConfigurationMemory.kt",
    "shared/src/main/java/com/shilapi/xcertplay/network/P2pOwnership.kt",
    "shared/src/main/java/com/shilapi/xcertplay/network/P2pStartupRecovery.kt",
    "shared/src/main/java/com/shilapi/xcertplay/network/WifiP2pGroupManager.kt",
    "shared/src/main/java/com/shilapi/xcertplay/network/WifiScanPause.kt",
    "shared/src/main/java/com/shilapi/xcertplay/network/WifiScanPauseSession.kt",
    "shared/src/main/java/com/shilapi/xcertplay/network/WirelessHostAddress.kt",
    "shared/src/main/java/com/shilapi/xcertplay/network/WirelessHotspotManager.kt",
    "shared/src/main/java/com/shilapi/xcertplay/network/WirelessReceiveDiagnostics.kt",
    "shared/src/main/java/com/shilapi/xcertplay/network/WirelessStartupDiagnostics.kt",
    "shared/src/main/java/com/shilapi/xcertplay/orchestration/CarPlayController.kt",
    "shared/src/main/java/com/shilapi/xcertplay/shared/MyCarAppScreen.kt",
    "shared/src/main/java/com/shilapi/xcertplay/shared/MyCarAppService.kt",
    "shared/src/main/java/com/shilapi/xcertplay/shared/MyCarAppSession.kt",
    "shared/src/main/java/com/shilapi/xcertplay/media/AndroidMediaSink.kt",
    "shared/src/main/java/com/shilapi/xcertplay/mfi/RemoteMfiAuthenticationClient.kt",
}

FORBIDDEN = {
    "java.time.": "java.time is unavailable on Android 4.2",
    "java.util.Base64": "use android.util.Base64",
    "java.nio.file.": "java.nio.file is unavailable on Android 4.2",
    "java.nio.charset.StandardCharsets": "StandardCharsets requires API19; use Charset.forName on API17",
    "java.util.stream.": "Java streams are unavailable on Android 4.2",
    "java.util.Optional": "java.util.Optional is unavailable on Android 4.2",
    "java.util.function.": "java.util.function is unavailable on Android 4.2",
    "CompletableFuture": "CompletableFuture is unavailable on Android 4.2",
    "ProcessHandle": "ProcessHandle is unavailable on Android 4.2",
    "Long.toUnsignedString(": "Java 8 unsigned helper is unavailable on Jelly Bean",
    "Integer.toUnsignedString(": "Java 8 unsigned helper is unavailable on Jelly Bean",
    "Long.parseUnsignedLong(": "Java 8 unsigned helper is unavailable on Jelly Bean",
    "Integer.parseUnsignedInt(": "Java 8 unsigned helper is unavailable on Jelly Bean",
    "Math.floorDiv(": "Java 8 Math.floorDiv is unavailable on Jelly Bean",
    "Math.floorMod(": "Java 8 Math.floorMod is unavailable on Jelly Bean",
    "Math.addExact(": "Java 8 Math.addExact is unavailable on Jelly Bean",
    "Math.subtractExact(": "Java 8 Math.subtractExact is unavailable on Jelly Bean",
    "Math.multiplyExact(": "Java 8 Math.multiplyExact is unavailable on Jelly Bean",
    "Math.toIntExact(": "Java 8 Math.toIntExact is unavailable on Jelly Bean",
    "String.join(": "Java 8 String.join is unavailable on Jelly Bean",
    "Comparator.comparing(": "Java 8 Comparator factory is unavailable on Jelly Bean",
    ".computeIfAbsent(": "Java 8 Map.computeIfAbsent is unavailable on Jelly Bean",
    ".computeIfPresent(": "Java 8 Map.computeIfPresent is unavailable on Jelly Bean",
    ".removeIf(": "Java 8 Collection.removeIf is unavailable on Jelly Bean",
}

failures = []
for root in ROOTS:
    if not root.exists():
        continue
    for source in sorted(root.rglob("*.kt")):
        relative = source.as_posix()
        if relative in EXCLUDED_FILES or relative.startswith(EXCLUDED_PREFIXES):
            continue
        in_block = False
        for number, raw in enumerate(source.read_text(encoding="utf-8").splitlines(), 1):
            line = raw
            stripped = line.lstrip()
            if in_block:
                if "*/" in line:
                    in_block = False
                    line = line.split("*/", 1)[1]
                else:
                    continue
            if stripped.startswith("/*"):
                if "*/" not in stripped[2:]:
                    in_block = True
                continue
            if stripped.startswith("//") or stripped.startswith("*"):
                continue
            code = line.split("//", 1)[0]
            for token, reason in FORBIDDEN.items():
                if token in code:
                    failures.append(f"{source}:{number}: {reason}: {token}")

if failures:
    print("API17-incompatible Java library usage found:", file=sys.stderr)
    print("\n".join(failures), file=sys.stderr)
    sys.exit(1)

print("API17 Java library scan passed")
