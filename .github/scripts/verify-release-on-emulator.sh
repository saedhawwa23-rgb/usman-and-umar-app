#!/usr/bin/env bash
set -euo pipefail
apk="$GITHUB_WORKSPACE/app/build/outputs/apk/release/app-release.apk"
adb install -r "$apk"
adb shell pm path com.usmanandumar.app | tee "$GITHUB_WORKSPACE/dist/INSTALL_PACKAGE_PATH.txt"
version_code=$(sed -n 's/.*versionCode \([0-9][0-9]*\).*/\1/p' "$GITHUB_WORKSPACE/app/build.gradle" | head -1)
version_name=$(sed -n 's/.*versionName "\([^"]*\)".*/\1/p' "$GITHUB_WORKSPACE/app/build.gradle" | head -1)
test -n "$version_code"
test -n "$version_name"
adb shell dumpsys package com.usmanandumar.app \
  | grep -E 'versionCode=|versionName=' \
  | tee "$GITHUB_WORKSPACE/dist/INSTALL_VERSION.txt"
grep -F "versionCode=$version_code" "$GITHUB_WORKSPACE/dist/INSTALL_VERSION.txt"
grep -F "versionName=$version_name" "$GITHUB_WORKSPACE/dist/INSTALL_VERSION.txt"
adb shell monkey -p com.usmanandumar.app 1 >/dev/null
adb shell dumpsys activity activities \
  | grep -F 'com.usmanandumar.app' \
  | tee "$GITHUB_WORKSPACE/dist/INSTALL_LAUNCH.txt"
