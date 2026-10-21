# WatchBoy Advance

Wear OS GBA emulator for circular watch displays, built for the Galaxy Watch7 ARM64 target.

## First working version

- A native Wear OS activity with a round ROM library and in-game display.
- Pinned mGBA 0.10.5 libretro core, built from source and loaded as a separate ARM64 shared library through a JNI bridge. mGBA is MPL-2.0; upstream bundled notices are packaged under `app/src/main/assets/licenses/`.
- Import `.gba`, `.agb`, or `.bin` files with Android's document picker. The app copies selected files into app-private storage and never bundles a commercial ROM or BIOS.
- Recent ROM list; tap a game to launch or resume it.
- Horizontal swipes map to left/right, a downward swipe maps to Down, an upper-left tap maps to Up, and an upward flick / upper-center tap maps to A. Lower corners send B/A; lower-center tap sends Start and long-press sends Select. Upper-right tap sends R and upper-left long-press sends L. Touch-and-hold along the lower sides provides continuous left/right input.
- 60 Hz frame loop, RGB565 video, stereo PCM output resampled to the device's native output rate, and per-ROM atomic quick-resume snapshots on app pause.
- ROM save data and quick-resume state remain in the app's private `files/roms` directory.

## Build and package verification

Requirements: JDK 17, Android SDK 35, NDK 27.2.12479018, CMake 3.22.1, and Gradle 8.9.

```sh
git clone --recurse-submodules https://github.com/ether4o4/GameboyAdvanceSPEmulator-.git
cd GameboyAdvanceSPEmulator-
git submodule update --init --recursive
gradle :app:assembleDebug
python3 tools/verify-apk.py app/build/outputs/apk/debug/app-debug.apk
```

GitHub Actions builds the ARM64 debug APK and verifies that both native libraries and license assets are packaged, the archive is intact, and no game ROM is bundled. The `WatchBoy-Advance-debug` workflow artifact is the installable APK.

## Verification limits

The source has a pinned upstream core and native build configuration. APK packaging and the bundled mGBA core are verified by the Android CI workflow. Physical Galaxy Watch7 testing is still needed to validate the document-picker provider available on the watch, touch feel, audio behavior, sustained frame pacing, and battery use. mGBA's upstream project does not ship an official Android app; this Wear OS frontend and JNI integration are project code.
