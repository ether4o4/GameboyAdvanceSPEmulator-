# Wear OS GBA Emulator Architecture

Target Galaxy Watch7 ARM64, 450x450 circular display.

Modules: Kotlin Wear OS shell; JNI bridge to a licensed open-source GBA core; frame rendering SurfaceView; AudioTrack PCM output; Android document picker for user-provided ROMs; app-private save-state storage. Investigate mGBA compatibility and its license obligations before bundling. Maintain an adjustable virtual control overlay, with gesture mapping and accessibility settings. Pause emulation on lifecycle stop and throttle CPU to protect watch battery.

Acceptance: boot legally obtained/homebrew GBA ROM, correct video/audio, save/load, resume, stable touch input, verified Watch7 APK. No ROMs bundled.
