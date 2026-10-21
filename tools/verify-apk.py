#!/usr/bin/env python3
import pathlib
import sys
import zipfile

apk = pathlib.Path(sys.argv[1])
with zipfile.ZipFile(apk) as archive:
    names = set(archive.namelist())
    bad_member = archive.testzip()
    if bad_member:
        raise SystemExit(f"corrupt APK member: {bad_member}")
    required = {
        "classes.dex",
        "lib/arm64-v8a/libwatchboy.so",
        "lib/arm64-v8a/libmgba_libretro.so",
        "assets/licenses/THIRD-PARTY-NOTICES.txt",
        "assets/licenses/MPL-2.0.txt",
        "assets/licenses/blip_buf-LGPL-2.1.txt",
        "assets/licenses/libretro-api-MIT.txt",
    }
    missing = sorted(required - names)
    if missing:
        raise SystemExit("APK is missing required entries: " + ", ".join(missing))
    native = sorted(n for n in names if n.startswith("lib/"))
    if any("x86" in n or "armeabi" in n for n in native):
        raise SystemExit("APK contains an unexpected non-ARM64 ABI")
    if any(n.lower().endswith((".gba", ".agb", ".rom")) for n in names):
        raise SystemExit("APK unexpectedly bundles a game ROM")
print(f"APK packaging verified: {apk} ({apk.stat().st_size} bytes)")
print("Contains WatchBoy JNI, separate mGBA core, license assets, ARM64 only, no ROM")
