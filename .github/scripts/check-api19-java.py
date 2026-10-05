#!/usr/bin/env python3
from pathlib import Path
import sys

ROOTS = [Path("shared/src/main/java"), Path("mobile/src/main/java")]

# Complete upstream DiPlay is kept in this repository, but the CR-V/API19 runtime does not
# compile/use every upstream feature. Exclude upstream-only surfaces from the legacy runtime scan.
EXCLUDED_PREFIXES = (
    "shared/src/main/java/com/shilapi/xcertplay/adb/",
    "shared/src/main/java/com/shilapi/xcertplay/hud/",
    "shared/src/main/java/com/shilapi/xcertplay/glance/",
)
EXCLUDED_FILES = {
    "shared/src/main/java/com/shilapi/xcertplay/network/CarPlayBonjour.kt",
    "shared/src/main/java/com/shilapi/xcertplay/network/LocalOnlyHotspotManager.kt",
    "shared/src/main/java/com/shilapi/xcertplay/network/ManualHotspotInterfaces.kt",
    "shared/src/main/java/com/shilapi/xcertplay/network/WifiP2pGroupManager.kt",
    "shared/src/main/java/com/shilapi/xcertplay/network/CarHotspotSettings.kt",
    "shared/src/main/java/com/shilapi/xcertplay/network/CarHotspotTethering.kt",
    "shared/src/main/java/com/shilapi/xcertplay/network/HotspotJoinRepair.kt",
    "shared/src/main/java/com/shilapi/xcertplay/network/HotspotJoinRepairMain.kt",
    "shared/src/main/java/com/shilapi/xcertplay/network/ManualHotspotManager.kt",
    "shared/src/main/java/com/shilapi/xcertplay/network/WifiScanPause.kt",
    "shared/src/main/java/com/shilapi/xcertplay/orchestration/CarPlayController.kt",
    "shared/src/main/java/com/shilapi/xcertplay/shared/MyCarAppScreen.kt",
    "shared/src/main/java/com/shilapi/xcertplay/shared/MyCarAppService.kt",
    "shared/src/main/java/com/shilapi/xcertplay/shared/MyCarAppSession.kt",
    "shared/src/main/java/com/shilapi/xcertplay/media/AndroidMediaSink.kt",
    "shared/src/main/java/com/shilapi/xcertplay/mfi/RemoteMfiAuthenticationClient.kt",
}

FORBIDDEN = {
    "java.time.": "java.time is unavailable on Android 4.4",
    "java.util.Base64": "use android.util.Base64",
    "java.nio.file.": "java.nio.file is unavailable on Android 4.4",
    "java.util.stream.": "Java streams are unavailable on Android 4.4",
    "java.util.Optional": "java.util.Optional is unavailable on Android 4.4",
    "java.util.function.": "java.util.function is unavailable on Android 4.4",
    "CompletableFuture": "CompletableFuture is unavailable on Android 4.4",
    "ProcessHandle": "ProcessHandle is unavailable on Android 4.4",
    "Long.toUnsignedString(": "Java 8 unsigned helper is unavailable on KitKat",
    "Integer.toUnsignedString(": "Java 8 unsigned helper is unavailable on KitKat",
    "Long.parseUnsignedLong(": "Java 8 unsigned helper is unavailable on KitKat",
    "Integer.parseUnsignedInt(": "Java 8 unsigned helper is unavailable on KitKat",
    "Math.floorDiv(": "Java 8 Math.floorDiv is unavailable on KitKat",
    "Math.floorMod(": "Java 8 Math.floorMod is unavailable on KitKat",
    "Math.addExact(": "Java 8 Math.addExact is unavailable on KitKat",
    "Math.subtractExact(": "Java 8 Math.subtractExact is unavailable on KitKat",
    "Math.multiplyExact(": "Java 8 Math.multiplyExact is unavailable on KitKat",
    "Math.toIntExact(": "Java 8 Math.toIntExact is unavailable on KitKat",
    "String.join(": "Java 8 String.join is unavailable on KitKat",
    "Comparator.comparing(": "Java 8 Comparator factory is unavailable on KitKat",
    ".computeIfAbsent(": "Java 8 Map.computeIfAbsent is unavailable on KitKat",
    ".computeIfPresent(": "Java 8 Map.computeIfPresent is unavailable on KitKat",
    ".removeIf(": "Java 8 Collection.removeIf is unavailable on KitKat",
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
    print("API19-incompatible Java library usage found:", file=sys.stderr)
    print("\n".join(failures), file=sys.stderr)
    sys.exit(1)

print("API19 Java library scan passed")
