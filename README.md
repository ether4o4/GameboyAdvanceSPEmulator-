# WatchBoy Advance

Wear OS / Galaxy Watch7 GBA emulator project.

## Scope
- Kotlin Wear OS circular ROM picker, swipe controls, and persistent preferences.
- Integrate an appropriately licensed GBA core (evaluate mGBA/MPL-2.0) using Android ARM64 JNI.
- Import user-provided legally obtained ROMs through Android document picker. No commercial ROMs or BIOS bundled.
- Audio, save states, frame pacing, and device profiling are required before release.

## Watch-first refinements
1. Adjustable gesture sensitivity and handedness.
2. Quick Resume with atomic state writes and recovery.
3. Recent/favorite ROM library with metadata stored locally.

## Acceptance checks
- Build and install APK on Watch7.
- Import and boot an openly licensed test ROM.
- Verify gestures, audio, saves across restart, and rotation/lifecycle behavior.
- Verify invalid ROM and missing file handling.
- Measure frame pacing and battery impact on device.

**Status:** Specification only. No emulator binary, JNI integration, or APK has been verified.
