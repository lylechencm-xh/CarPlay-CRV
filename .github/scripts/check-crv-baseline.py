#!/usr/bin/env python3
from pathlib import Path
import re
import sys

checks = {
    Path("mobile/build.gradle.kts"): r"minSdk\s*=\s*17\b",
    Path("crvlegacy/build.gradle"): r"minSdk\s*=\s*17\b",
}

failures = []
for path, pattern in checks.items():
    text = path.read_text(encoding="utf-8")
    if not re.search(pattern, text):
        failures.append(f"{path}: CR-V baseline must remain minSdk=17 (Android 4.2.2)")

workflow = Path(".github/workflows/crv-2021.yml").read_text(encoding="utf-8")
for required in ("Android 4.2.2", "api17", "check-api17-java.py"):
    if required not in workflow:
        failures.append(f"workflow baseline marker missing: {required}")
if not re.search(r"minSdk\s*[:=]\s*17\b", workflow):
    failures.append("workflow baseline marker missing: minSdk 17")

if failures:
    print("CR-V API17 baseline violation:", file=sys.stderr)
    print("\n".join(failures), file=sys.stderr)
    sys.exit(1)

print("CR-V Android 4.2.2 / API17 baseline locked")
