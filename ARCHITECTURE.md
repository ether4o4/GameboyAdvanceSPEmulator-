# Wear OS GBA Emulator Architecture

Target: Galaxy Watch7 ARM64, circular Wear OS display.

The Kotlin activity owns the ROM picker, document import, round-screen Canvas rendering, touch gestures, frame scheduling, and AudioTrack output. The JNI bridge owns the libretro frontend callbacks, RGB565 frame copy, joypad state, interleaved PCM queue, and atomic savestate files. The mGBA 0.10.5 source is a pinned submodule and its `mgba_libretro` target is built as a separate shared library.

ROMs are imported through `ACTION_OPEN_DOCUMENT`, copied to app-private storage, and opened by their internal path. No game data or BIOS is shipped. Per-ROM savestates are restored when that ROM is opened; native saves use the app-private ROM directory. The engine pauses and saves when the activity backgrounds.

Touch input maps horizontal swipes to left/right, downward swipe to Down, upper-left tap to Up, upper-center tap/upward flick to A, lower corners to B/A, lower-center tap/hold to Start/Select, upper-right tap to R, upper-left long-press to L, and held lower-side touch to continuous horizontal direction. Frame and sound pacing still need physical Watch7 measurements.

CI assembles an ARM64 debug APK and checks its ZIP integrity, JNI/core libraries, license assets, and no-ROM packaging. Watch-specific import, gesture, audio, and performance acceptance remains a physical device task.
