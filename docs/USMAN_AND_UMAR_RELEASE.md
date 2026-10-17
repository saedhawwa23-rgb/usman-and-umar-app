# USMAN AND UMAR v1.0.2 Release Notes

## Summary

This release establishes **USMAN AND UMAR** as a separately maintained Android project based on the official OpenDroid foundation.

## Changes

- Renamed the Android application label and accessibility-service description to `USMAN AND UMAR`.
- Set minimum SDK 28 and target SDK 34.
- Set versionCode 3 and versionName 1.0.2.
- Restricted packaged native ABI to `armeabi-v7a` for the 32-bit ARM APK.
- Disabled the large heap request to suit devices with approximately 2.7 GB RAM.
- Removed the Linux/Ubuntu package-install helper from the repository.
- Kept the existing LiteRT-LM Android provider dependency because its provider implementation is part of the imported OpenDroid source; this is an Android-native library and not a Linux/VM/desktop runtime.
- Retained Android-native Accessibility Service integration as the primary automation mechanism.

## Validation

Before publishing, verify the release artifact with:

```bash
./gradlew assembleRelease -PallowUnsignedRelease=true
apkanalyzer manifest application-id app/build/outputs/apk/release/app-release-unsigned.apk
apkanalyzer manifest min-sdk app/build/outputs/apk/release/app-release-unsigned.apk
apkanalyzer manifest target-sdk app/build/outputs/apk/release/app-release-unsigned.apk
apkanalyzer apk summary app/build/outputs/apk/release/app-release-unsigned.apk
```

The APK should report application label `USMAN AND UMAR`, application ID `com.usmanandumar.app`, minSdk 28, targetSdk 34, and contain only the `armeabi-v7a` native ABI where applicable.

## v1.0.2 packaging

The v1.0.2 release changes the application ID to `com.usmanandumar.app`, packages only `armeabi-v7a`, and is signed with a dedicated release keystore using alias `usman`. The final APK must contain no `android:testOnly="true"` manifest attribute.
