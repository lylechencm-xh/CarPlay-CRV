#!/usr/bin/env python3
from pathlib import Path
import sys

ROOTS = [Path("shared/src/main/java"), Path("mobile/src/main/java")]
FORBIDDEN = {
    "java.time.": "java.time is unavailable on Android 4.2",
    "java.util.Base64": "use android.util.Base64",
    "java.nio.file.": "java.nio.file is unavailable on Android 4.2",
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
