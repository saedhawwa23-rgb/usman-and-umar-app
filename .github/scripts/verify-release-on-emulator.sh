#!/usr/bin/env bash
set -euo pipefail
apk="$GITHUB_WORKSPACE/app/build/outputs/apk/release/app-release.apk"
adb install -r "$apk"
adb shell pm path com.usmanandumar.app | tee "$GITHUB_WORKSPACE/dist/INSTALL_PACKAGE_PATH.txt"
adb shell dumpsys package com.usmanandumar.app \
  | grep -E 'versionCode=|versionName=' \
  | tee "$GITHUB_WORKSPACE/dist/INSTALL_VERSION.txt"
grep -F 'versionCode=37' "$GITHUB_WORKSPACE/dist/INSTALL_VERSION.txt"
grep -F 'versionName=3.10.7' "$GITHUB_WORKSPACE/dist/INSTALL_VERSION.txt"
adb shell monkey -p com.usmanandumar.app 1 >/dev/null
adb shell dumpsys activity activities \
  | grep -F 'com.usmanandumar.app' \
  | tee "$GITHUB_WORKSPACE/dist/INSTALL_LAUNCH.txt"
