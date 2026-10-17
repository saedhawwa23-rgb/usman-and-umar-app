# Supported device targets

## Official targets

USMAN AND UMAR targets the following Samsung devices and keeps device-specific behavior inside Android capability checks rather than model-name checks.

| Device target | Android target | CPU/ABI target | RAM/storage profile | Support status |
|---|---|---|---|---|
| Samsung Galaxy A07 4G, 64 GB (`SM-A075F` where applicable) | Android 15/16 | ARMv7 or ARM64 | 4 GB / 64 GB | **UNVERIFIED — REQUIRES DEVICE** |
| Samsung Galaxy M10 (`SM-M105F` / `SM-M105G`) | Android 9/10 update | 64-bit ARM, ARMv7 package path retained | 2–3 GB / 16–32 GB | **UNVERIFIED — REQUIRES DEVICE** |

The app currently has `minSdk 28` (Android 9). A Galaxy M10 that remains on its original Android 8.1/API 27 firmware is **not install-compatible** with the current release. M10 devices updated to Android 9 or Android 10 are within the supported range. Lowering `minSdk` to API 27 would require a separate compatibility change and full regression pass; it is not claimed by this release.

## Compatibility guarantees checked in source/build configuration

- `applicationId`: `com.usmanandumar.app`
- `minSdk`: 28 / Android 9
- `targetSdk`: 34
- Native packaging: `armeabi-v7a` and `arm64-v8a`
- `largeHeap`: disabled; the application does not request a large heap
- Telephony and camera features are declared optional
- Accessibility service uses `BIND_ACCESSIBILITY_SERVICE` and retrieves interactive windows
- Foreground service behavior selects microphone or `specialUse` types according to API level and permission state
- Android 15 boot restrictions for microphone foreground services are handled by falling back to `specialUse`
- WorkManager is used for scheduled background jobs; no LLM model downloads are packaged or scheduled
- Notifications use a low-importance foreground-service channel
- Automation is capability-driven; unavailable hardware actions return a controlled result rather than assuming hardware exists

## UNVERIFIED — REQUIRES DEVICE: Physical-device validation matrix

The following checks cannot be certified by a JVM test, APK inspection, or an emulator substitute. They require the named Samsung device and firmware.

### Installation and lifecycle

- Install a clean APK and launch it.
- Upgrade over the same signed package while preserving app data.
- Cold start, force-stop/restart, and device reboot.
- Confirm no crash or ANR during normal startup.

### Automation

- Enable **USMAN AND UMAR** under Settings → Accessibility.
- Verify window-content retrieval, view IDs, gestures, text entry, scrolling, Back/Home, and screenshots.
- Exercise safe automation and approval-gated sensitive actions.
- Repeat with portrait and normal phone layouts.

### Background and Samsung power management

- Start the foreground agent and verify its notification.
- Test screen-off and screen-on behavior.
- Test Doze, app standby, and Samsung battery optimization behavior.
- For uninterrupted background automation, verify the user-facing battery setting is configured to **Unrestricted** where Samsung firmware exposes that control.
- Test boot receiver behavior and confirm Android 15 microphone-start restrictions do not produce an exception.

### Permissions and integrations

- Notifications and runtime microphone permission.
- Accessibility and notification-listener toggles.
- Network loss/recovery.
- Termux/Shizuku only when separately installed and authorized.
- Configure a cloud provider, run a simple response, and exercise a read-only tool call.
- GitHub public search → open live repository details → ADD/USE project context → Termux clone → inspect README/root-file results.
- Verify Termux stdout, stderr, and exit-code callback capture on the installed Termux version.

## Current verification boundary

The repository/build checks cover manifest, SDK, ABI, service declarations, and static compatibility. No Galaxy A07 or Galaxy M10 is attached to this execution environment. Every physical-device check above is therefore **UNVERIFIED — REQUIRES DEVICE**, not a PASS. A device test report must attach device model, Android build, battery-policy setting, test case, and result for each row.
