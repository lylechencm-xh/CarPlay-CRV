#!/usr/bin/env python3
"""Verify the final CR-V APK against the Android 4.2.2 / API17 runtime surface."""
from pathlib import Path
from zipfile import ZipFile
import os
import struct
import sys

apk = Path(sys.argv[1]) if len(sys.argv) > 1 else Path("mobile/build/outputs/apk/debug/mobile-debug.apk")
sdk_root = Path(os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT") or "")
platform = sdk_root / "platforms" / "android-17" / "android.jar"

if not apk.is_file():
    raise SystemExit(f"APK not found: {apk}")
if not platform.is_file():
    raise SystemExit(f"Android 17 platform jar not found: {platform}")

with ZipFile(platform) as sdk:
    available = {"L" + name[:-6] + ";" for name in sdk.namelist() if name.endswith(".class")}
    platform_bytes = {name[:-6]: sdk.read(name) for name in sdk.namelist() if name.endswith(".class")}

with ZipFile(apk) as package:
    bad = package.testzip()
    assert bad is None, f"APK contains a corrupt ZIP member: {bad}"

    native = sorted(name for name in package.namelist() if name.startswith("lib/") and name.endswith(".so"))
    allowed_native = {
        "lib/armeabi-v7a/libcrvusbfs.so",
        "lib/x86/libcrvusbfs.so",
    }
    unexpected_native = sorted(set(native) - allowed_native)
    assert not unexpected_native, "Unexpected native libraries: " + str(unexpected_native)
    assert "lib/armeabi-v7a/libcrvusbfs.so" in native, "CR-V armeabi-v7a usbfs bridge missing"

    dex_names = sorted(name for name in package.namelist() if name.endswith(".dex"))
    assert dex_names == ["classes.dex"], "API17 build must remain single-Dex: " + str(dex_names)
    data = package.read("classes.dex")
    assert data[:8] == b"dex\n035\0", f"API17 build must use DEX035, got {data[:8]!r}"

    def u32(offset):
        return struct.unpack_from("<I", data, offset)[0]

    def uleb(cursor):
        value = 0
        shift = 0
        while True:
            byte = data[cursor]
            cursor += 1
            value |= (byte & 0x7F) << shift
            if byte < 0x80:
                return value, cursor
            shift += 7

    strings = []
    for i in range(u32(56)):
        cursor = u32(u32(60) + i * 4)
        _, cursor = uleb(cursor)
        end = data.index(0, cursor)
        strings.append(data[cursor:end].decode("utf-8", errors="replace"))

    types = [strings[u32(u32(68) + i * 4)] for i in range(u32(64))]
    defined = {types[u32(u32(100) + i * 32)] for i in range(u32(96))}
    vm_annotations = {
        "Ldalvik/annotation/" + name + ";"
        for name in [
            "AnnotationDefault", "EnclosingClass", "EnclosingMethod", "InnerClass",
            "MemberClasses", "Signature", "SourceDebugExtension", "Throws",
        ]
    }
    platform_prefixes = (
        "Ljava/", "Ljavax/", "Landroid/", "Ldalvik/",
        "Lsun/", "Lcom/sun/", "Lorg/apache/harmony/",
    )
    missing_types = sorted(
        t for t in types
        if t.startswith(platform_prefixes) and t not in available and t not in defined and t not in vm_annotations
    )
    assert not missing_types, "Platform types unavailable on API17: " + str(missing_types)

    cache = {}

    def class_members(name):
        if name in cache:
            return cache[name]
        raw = platform_bytes.get(name)
        if raw is None:
            return ([], set(), set())
        cursor = 8

        def short():
            nonlocal cursor
            result = struct.unpack_from(">H", raw, cursor)[0]
            cursor += 2
            return result

        def integer():
            nonlocal cursor
            result = struct.unpack_from(">I", raw, cursor)[0]
            cursor += 4
            return result

        cp = [None] * short()
        i = 1
        while i < len(cp):
            tag = raw[cursor]
            cursor += 1
            if tag == 1:
                length = short()
                cp[i] = raw[cursor:cursor + length].decode("utf-8", errors="replace")
                cursor += length
            elif tag in (7, 8, 16, 19, 20):
                cp[i] = short()
            elif tag in (3, 4, 9, 10, 11, 12, 17, 18):
                cursor += 4
            elif tag in (5, 6):
                cursor += 8
                i += 1
            elif tag == 15:
                cursor += 3
            else:
                raise AssertionError(f"Unsupported class constant-pool tag {tag} in {name}")
            i += 1

        short()
        short()
        superclass = short()
        parents = [cp[cp[superclass]]] if superclass else []
        parents += [cp[cp[short()]] for _ in range(short())]
        members = []
        for _ in range(2):
            group = set()
            for _ in range(short()):
                short()
                member_name = cp[short()]
                descriptor = cp[short()]
                group.add((member_name, descriptor))
                for _ in range(short()):
                    short()
                    size = integer()
                    cursor += size
            members.append(group)
        result = (parents, members[0], members[1])
        cache[name] = result
        return result

    def exists(owner, name, descriptor, kind, seen=None):
        seen = set() if seen is None else seen
        if owner in seen:
            return False
        seen.add(owner)
        parents, fields, methods = class_members(owner)
        if (name, descriptor) in (methods if kind == "method" else fields):
            return True
        if name == "<init>":
            return False
        return any(exists(parent, name, descriptor, kind, seen) for parent in parents)

    unavailable = []
    for i in range(u32(88)):
        offset = u32(92) + i * 8
        owner_id, proto_id, name_id = struct.unpack_from("<HHI", data, offset)
        owner = types[owner_id]
        if owner not in available or owner in defined:
            continue
        proto = u32(76) + proto_id * 12
        parameter_offset = u32(proto + 8)
        parameters = (
            [types[struct.unpack_from("<H", data, parameter_offset + 4 + j * 2)[0]]
             for j in range(u32(parameter_offset))]
            if parameter_offset else []
        )
        descriptor = "(" + "".join(parameters) + ")" + types[u32(proto + 4)]
        if not exists(owner[1:-1], strings[name_id], descriptor, "method"):
            unavailable.append(owner + "->" + strings[name_id] + descriptor)

    for i in range(u32(80)):
        owner_id, type_id, name_id = struct.unpack_from("<HHI", data, u32(84) + i * 8)
        owner = types[owner_id]
        if owner not in available or owner in defined:
            continue
        if not exists(owner[1:-1], strings[name_id], types[type_id], "field"):
            unavailable.append(owner + "->" + strings[name_id] + ":" + types[type_id])

    assert not unavailable, "Platform members unavailable on API17: " + str(sorted(set(unavailable)))
    print("PASS: final APK is single-Dex DEX035 and uses only API17 platform types/members")
    print("DEX method references:", u32(88))
    print("Native libraries:", ",".join(native))
