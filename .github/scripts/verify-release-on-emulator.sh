#!/usr/bin/env bash
set -euo pipefail

package=com.usmanandumar.app
apk="$GITHUB_WORKSPACE/app/build/outputs/apk/release/app-release.apk"
evidence="$GITHUB_WORKSPACE/dist"
mkdir -p "$evidence"

# This is a disposable GitHub Actions emulator, not a user's device. Clear only this CI install so
# the first-run onboarding path is deterministic; no physical/user installation is touched.
package_manager_ready=false
for _ in $(seq 1 60); do
  if adb shell cmd package list packages >/dev/null 2>&1; then
    package_manager_ready=true
    break
  fi
  sleep 5
done
if [[ "$package_manager_ready" != true ]]; then
  adb logcat -d -b all > "$evidence/EMULATOR_LOGCAT_PACKAGE_NOT_READY.txt" || true
  adb shell dumpsys meminfo > "$evidence/EMULATOR_MEMINFO_PACKAGE_NOT_READY.txt" 2>&1 || true
  echo "Package manager did not become ready after emulator boot" >&2
  exit 1
fi
if ! adb install --no-streaming -r "$apk"; then
  adb logcat -d -b all > "$evidence/EMULATOR_LOGCAT_INSTALL_FAILURE.txt" || true
  adb shell dumpsys meminfo > "$evidence/EMULATOR_MEMINFO_INSTALL_FAILURE.txt" 2>&1 || true
  exit 1
fi
adb shell pm clear "$package" | tee "$evidence/EMULATOR_DATA_RESET.txt"
adb shell pm path "$package" | tee "$evidence/INSTALL_PACKAGE_PATH.txt"

version_code=$(sed -n 's/.*versionCode \([0-9][0-9]*\).*/\1/p' "$GITHUB_WORKSPACE/app/build.gradle" | head -1)
version_name=$(sed -n 's/.*versionName "\([^"]*\)".*/\1/p' "$GITHUB_WORKSPACE/app/build.gradle" | head -1)
test -n "$version_code"
test -n "$version_name"
adb shell dumpsys package "$package" \
  | grep -E 'versionCode=|versionName=' \
  | tee "$evidence/INSTALL_VERSION.txt"
grep -F "versionCode=$version_code" "$evidence/INSTALL_VERSION.txt"
grep -F "versionName=$version_name" "$evidence/INSTALL_VERSION.txt"

capture_ui() {
  local name="$1"
  adb shell uiautomator dump /sdcard/window.xml >/dev/null
  adb exec-out cat /sdcard/window.xml > "$evidence/${name}.xml"
  adb exec-out screencap -p > "$evidence/${name}.png"
}

ui_has_text() {
  local needle="$1" xml="$2"
  python3 - "$needle" "$xml" <<'PY'
import sys
import xml.etree.ElementTree as ET
needle, path = sys.argv[1:]
root = ET.parse(path).getroot()
for node in root.iter():
    if needle in node.attrib.get("text", "") or needle in node.attrib.get("content-desc", ""):
        raise SystemExit(0)
raise SystemExit(1)
PY
}

wait_for_text() {
  local needle="$1" name="$2"
  local xml="$evidence/${name}.xml"
  for _ in $(seq 1 60); do
    capture_ui "$name"
    if ui_has_text "$needle" "$xml"; then
      return 0
    fi
    sleep 1
  done
  echo "Expected UI text did not appear: $needle" >&2
  cat "$xml" >&2
  return 1
}

wait_for_absent_text() {
  local needle="$1" name="$2"
  local xml="$evidence/${name}.xml"
  for _ in $(seq 1 30); do
    capture_ui "$name"
    if ! ui_has_text "$needle" "$xml"; then
      return 0
    fi
    sleep 1
  done
  echo "UI text remained visible after navigation: $needle" >&2
  cat "$xml" >&2
  return 1
}

click_text() {
  local needle="$1" xml="$evidence/.current-ui.xml"
  adb shell uiautomator dump /sdcard/window.xml >/dev/null
  adb exec-out cat /sdcard/window.xml > "$xml"
  local coords
  coords=$(python3 - "$needle" "$xml" <<'PY'
import re
import sys
import xml.etree.ElementTree as ET
needle, path = sys.argv[1:]
root = ET.parse(path).getroot()
matches = []
for node in root.iter():
    if needle in node.attrib.get("text", "") or needle in node.attrib.get("content-desc", ""):
        bounds = re.fullmatch(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", node.attrib.get("bounds", ""))
        if bounds:
            x1, y1, x2, y2 = map(int, bounds.groups())
            matches.append(((y1 + y2) // 2, (x1 + x2) // 2))
if matches:
    # Prefer bottom-most matching text: action buttons and bottom navigation items can share
    # labels with top-bar headings or other screen content.
    y, x = max(matches)
    print(x, y)
    raise SystemExit(0)
raise SystemExit(f"Could not locate visible UI text to click: {needle}")
PY
  )
  read -r x y <<< "$coords"
  adb shell input tap "$x" "$y"
}

adb shell monkey -p "$package" 1 >/dev/null
wait_for_text "Hello! I am USMAN AND UMAR" "ONBOARDING_INTRO"
ui_has_text "What should I call you?" "$evidence/ONBOARDING_INTRO.xml"
ui_has_text "When is your birthday?" "$evidence/ONBOARDING_INTRO.xml"

# Enter synthetic-only data to proceed through the real encrypted profile-save path.
click_text "What should I call you?"
adb shell input text 'CI%stest'
adb shell input keyevent 4
click_text "When is your birthday?"
adb shell input text '01/15/1990'
adb shell input keyevent 4
capture_ui "ONBOARDING_PROFILE_ENTERED"
click_text "Let's Go"
wait_for_text "Permissions Setup" "ONBOARDING_PERMISSION_PROMPT"
click_text "Grant Permissions"
wait_for_text "Required Permissions" "ONBOARDING_GRANT_PERMISSIONS"
ui_has_text "Proceed to USMAN AND UMAR" "$evidence/ONBOARDING_GRANT_PERMISSIONS.xml"

# The screen explicitly permits continuing and granting remaining capabilities later in Settings.
# Do not grant elevated permissions as part of this smoke test.
click_text "Proceed to USMAN AND UMAR"
wait_for_text "Tasks" "DASHBOARD_CHAT"
ui_has_text "Chat" "$evidence/DASHBOARD_CHAT.xml"
click_text "Tasks"
wait_for_text "Task Bar" "DASHBOARD_TASKS"
click_text "Chat"
wait_for_absent_text "Task Bar" "DASHBOARD_RETURN_TO_CHAT"
ui_has_text "Chat" "$evidence/DASHBOARD_RETURN_TO_CHAT.xml"

adb shell dumpsys activity activities \
  | grep -F "$package" \
  | tee "$evidence/INSTALL_LAUNCH.txt"
