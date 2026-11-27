#!/bin/sh
set -eu

# Captures the real rendered screens of one application tree on an emulator.
#
# Usage: capture-emulator-screens.sh <workdir> <output-dir> <gradle-log> <expected-version> <screen...>
#
# The screen names are the ones the instrumentation test writes, and the count
# is enforced by verify-visual-evidence.py so a silently skipped capture fails
# the job instead of producing a short but "passing" artifact set.

workdir="$1"
output_dir="$2"
gradle_log="$3"
expected_version="$4"
shift 4
# Accept either a comma-separated list or separate arguments; the workflow passes
# one string because the emulator action runs this with sh, where bash-only
# array expansion is unavailable.
screens="$(printf '%s' "$*" | tr ',' ' ')"

package_name="${PACKAGE_NAME:?PACKAGE_NAME is required}"
screenshot_test="${SCREENSHOT_TEST:?SCREENSHOT_TEST is required}"

[ -n "$screens" ] || {
  echo "No expected screens passed" >&2
  exit 1
}

version="v$(sed -n 's/^[[:space:]]*versionName[[:space:]]*"\([^"]*\)".*/\1/p' "$workdir/app/build.gradle" | head -n 1)"
case "$version" in
  v[0-9]*) ;;
  *)
    echo "Could not resolve the app version from $workdir/app/build.gradle" >&2
    exit 1
    ;;
esac

if [ "$version" != "v$expected_version" ]; then
  echo "Expected app version $expected_version but $workdir declares $version" >&2
  exit 1
fi
echo "Capturing $workdir as $version"

mkdir -p "$output_dir" "$(dirname "$gradle_log")"
rm -f "$output_dir"/*.png

(
  cd "$workdir"
  ./gradlew :app:connectedDebugAndroidTest \
    -PinstrumentedTestAbi=x86_64 \
    -Pandroid.testInstrumentationRunnerArguments.class="$screenshot_test" \
    --no-configuration-cache --stacktrace
) >"$gradle_log" 2>&1 &
gradle_pid=$!

# The test writes into internal filesDir, which is only readable through
# `run-as`. Pull each screen as soon as it appears, validating the PNG magic
# before accepting it so a truncated pull is retried rather than kept.
for _ in $(seq 1 900); do
  for name in $screens; do
    target="$output_dir/$version-$name.png"
    if [ ! -s "$target" ]; then
      temp="$target.tmp"
      rm -f "$temp"
      if adb shell pm path "$package_name" >/dev/null 2>&1; then
        # Current trees capture into internal filesDir (only readable through
        # `run-as`); the v3.10.6 baseline captures into its external files dir.
        # Trying both keeps the historical tree unmodified.
        adb exec-out run-as "$package_name" cat "files/visual-evidence/$version-$name.png" >"$temp" 2>/dev/null || true
        if [ "$(head -c 8 "$temp" 2>/dev/null | od -An -tx1 | tr -d ' \n')" != "89504e470d0a1a0a" ]; then
          adb exec-out cat "/sdcard/Android/data/$package_name/files/visual-evidence/$version-$name.png" >"$temp" 2>/dev/null || true
        fi
        if [ "$(head -c 8 "$temp" 2>/dev/null | od -An -tx1 | tr -d ' \n')" = "89504e470d0a1a0a" ]; then
          mv "$temp" "$target"
        else
          rm -f "$temp"
        fi
      fi
    fi
  done
  if kill -0 "$gradle_pid" 2>/dev/null; then
    sleep 1
  else
    break
  fi
done

set +e
wait "$gradle_pid"
gradle_status=$?
set -e
cat "$gradle_log"
test "$gradle_status" -eq 0

missing=0
for name in $screens; do
  if [ ! -s "$output_dir/$version-$name.png" ]; then
    echo "Missing capture: $version-$name.png" >&2
    missing=1
  fi
done
test "$missing" -eq 0
