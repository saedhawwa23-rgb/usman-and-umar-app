# CI validation

## Instrumentation emulator ABI

The distributable USMAN AND UMAR APK is intentionally restricted to `armeabi-v7a` in `app/build.gradle` to preserve the target 32-bit ARM device requirement.

GitHub-hosted Ubuntu runners provide x86_64 Android emulators. Those emulators cannot execute the ARM-only application APK, which previously produced:

> No compatible devices connected. Found 1 connected device(s), 0 of which were compatible.

The instrumentation workflow therefore passes `-PinstrumentedTestAbi=x86_64`. This is a **test-only** override applied to the debug instrumentation build. Release builds and ordinary debug builds remain `armeabi-v7a` only.

The emulator workflow also uses a freshly created headless AVD, disables boot animation/audio/window rendering, and keeps KVM setup enabled. These settings address runner boot instability but cannot change the emulator's x86_64 architecture.

API 28 has completed successfully with this arrangement. API 36 previously timed out after the emulator process disappeared (`adb: device 'emulator-5554' not found` and TCP port 5554 refused), before any instrumentation test started. The workflow now gives API 36 a 20-minute boot window, allocates four emulator cores and 4 GB RAM, disables snapshots and spellchecking, and selects SwiftShader rendering. If API 36 still disappears after these settings, that remains a GitHub-hosted emulator failure rather than an app assertion or test failure.

## What the instrumentation checks cover

The workflow still runs the complete `androidTest` source set through `connectedDebugAndroidTest` on API 28 and API 36. It does not skip tests or select individual test classes.
